#!/usr/bin/env python3

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


TASKS_URL = "http://localhost:8080/api/tasks"

STATE_FILE = Path(
    "/tmp/forge_session_grace_restart_state.json"
)

MARKER = Path(
    "/tmp/forge_session_grace_restart.marker"
)

POLL = 0.20

REPO_ROOT = Path(__file__).resolve().parents[1]


def wait_until(predicate, description, timeout):

    deadline = time.time() + timeout

    while time.time() < deadline:

        value = predicate()

        if value:
            return value

        time.sleep(POLL)

    raise AssertionError(
        f"Timed out waiting for {description}"
    )


def sql_escape(value):

    return value.replace("'", "''")


def db_scalar(sql):

    environment = dict(
        os.environ
    )

    environment["PGPASSWORD"] = (
        "forge"
    )


    command = [
        "psql",
        "-h",
        "127.0.0.1",
        "-p",
        "5433",
        "-X",
        "-q",
        "-A",
        "-t",
        "-U",
        "forge",
        "-d",
        "forge",
        "-c",
        sql,
    ]


    result = subprocess.run(
        command,
        text=True,
        capture_output=True,
        env=environment,
    )


    if result.returncode != 0:

        raise RuntimeError(
            "Database command failed:\\n"
            + result.stderr
        )


    lines = [
        line.strip()
        for line in result.stdout.splitlines()
        if line.strip()
    ]


    if not lines:
        return None

    return lines[0]


def get_attempts(task_id):

    return helpers.get(
        f"{TASKS_URL}/{task_id}/attempts"
    )


def recovery_deadline(
        worker_id,
        session_id):

    worker = sql_escape(
        worker_id
    )

    session = sql_escape(
        session_id
    )


    value = db_scalar(
        f"""
        SELECT EXTRACT(
            EPOCH FROM recover_after
        )
        FROM worker_session_recoveries
        WHERE worker_id = '{worker}'
          AND session_id = '{session}';
        """
    )


    if value is None:
        return None

    return float(value)


def extend_recovery(
        worker_id,
        session_id):

    worker = sql_escape(
        worker_id
    )

    session = sql_escape(
        session_id
    )


    value = db_scalar(
        f"""
        UPDATE worker_session_recoveries
        SET recover_after =
            NOW() + INTERVAL '45 seconds'
        WHERE worker_id = '{worker}'
          AND session_id = '{session}'
        RETURNING EXTRACT(
            EPOCH FROM recover_after
        );
        """
    )


    if value is None:

        raise AssertionError(
            "No durable worker-session recovery "
            "row was created"
        )


    return float(value)


def recovery_count(
        worker_id,
        session_id):

    worker = sql_escape(
        worker_id
    )

    session = sql_escape(
        session_id
    )


    value = db_scalar(
        f"""
        SELECT COUNT(*)
        FROM worker_session_recoveries
        WHERE worker_id = '{worker}'
          AND session_id = '{session}';
        """
    )


    return int(value or "0")


def prepare():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge durable session grace restart test"
    )
    print(
        "========================================"
    )


    STATE_FILE.unlink(
        missing_ok=True
    )

    MARKER.unlink(
        missing_ok=True
    )


    # ========================================================
    # Session A
    # ========================================================

    worker_a = helpers.require_one_worker()

    worker_id = worker_a["id"]
    session_a = worker_a["sessionId"]

    pid_a = helpers.require_one_local_worker()


    print("Worker:", worker_id)
    print("Session A:", session_a)
    print("PID A:", pid_a)


    environment = (
        helpers.read_process_environment(
            pid_a
        )
    )


    # ========================================================
    # Create a task whose first execution produces no result
    # after its worker is killed.
    # ========================================================

    task_program = f"""
import os
import time
from pathlib import Path

marker = Path({str(MARKER)!r})

if marker.exists():
    print("grace-restart-retry-ok")
else:
    marker.touch()

    parent = os.getppid()

    while os.getppid() == parent:
        time.sleep(0.2)
"""


    print(
        "\n[CREATE] submitting retryable task"
    )


    task = helpers.post(
        TASKS_URL,
        {
            "command": "python3",
            "arguments": [
                "-c",
                task_program,
            ],
            "maxAttempts": 2,
            "timeoutSeconds": 90,
        },
    )


    task_id = task["id"]

    print("Task:", task_id)


    print(
        "[WAIT] waiting for attempt 1 RUNNING"
    )


    helpers.wait_task(
        task_id,
        "RUNNING",
        timeout=10,
    )


    wait_until(
        lambda: MARKER.exists(),
        "first execution marker",
        timeout=5,
    )


    attempts = get_attempts(
        task_id
    )


    if len(attempts) != 1:

        raise AssertionError(
            "Expected exactly one attempt"
        )


    attempt_a = attempts[0]

    attempt_a_id = attempt_a["id"]


    if attempt_a["workerSessionId"] \
            != session_a:

        raise AssertionError(
            "Attempt 1 is not owned by session A"
        )


    print("Attempt 1:", attempt_a_id)


    # ========================================================
    # Kill A
    # ========================================================

    print(
        "[CRASH] SIGKILL session A"
    )


    os.kill(
        pid_a,
        signal.SIGKILL,
    )


    # ========================================================
    # Start B. Its takeover should create the durable
    # retirement/recovery row for A.
    # ========================================================

    print(
        "[RESTART] starting session B"
    )


    process_b = helpers.launch_worker(
        environment
    )

    pid_b = process_b.pid


    worker_b = helpers.wait_for_session(
        worker_id,
        lambda worker:
            worker["sessionId"] != session_a
            and worker["online"]
            and worker[
                "commandStreamConnected"
            ],
        timeout=20,
    )


    session_b = worker_b["sessionId"]


    print("Session B:", session_b)
    print("PID B:", pid_b)


    # ========================================================
    # Verify durable grace exists, then lengthen only this
    # test row so a manual controller restart is practical.
    # ========================================================

    print(
        "[VERIFY] checking persisted recovery row"
    )


    deadline = extend_recovery(
        worker_id,
        session_a
    )


    print(
        "[VERIFY] durable recovery row exists"
    )

    print(
        "[TEST] extended recovery deadline "
        "to ~45 seconds"
    )


    # The old attempt must still be alive logically.
    task_now = helpers.get(
        f"{TASKS_URL}/{task_id}"
    )


    if task_now["status"] not in {
        "DISPATCHED",
        "RUNNING",
    }:

        raise AssertionError(
            "Attempt was recovered before grace. "
            f"Task status={task_now['status']}"
        )


    STATE_FILE.write_text(
        json.dumps(
            {
                "taskId": task_id,
                "attempt1Id": attempt_a_id,
                "workerId": worker_id,
                "sessionA": session_a,
                "sessionB": session_b,
                "pidB": pid_b,
                "deadlineEpoch": deadline,
            },
            indent=2,
        )
    )


    print()
    print(
        "PREPARE PASS"
    )

    print()
    print(
        "Restart the CONTROLLER now."
    )

    print()
    print(
        "Leave worker B running."
    )

    print()
    print(
        "Kill controller:"
    )

    print(
        "  sudo fuser -k 8080/tcp"
    )

    print(
        "  sudo fuser -k 50051/tcp"
    )

    print()
    print(
        "Restart it:"
    )

    print(
        "  cd ~/forge_app/controller"
    )

    print(
        "  ./gradlew bootRun"
    )

    print()
    print(
        "Then immediately run:"
    )

    print(
        "  cd ~/forge_app"
    )

    print(
        "  python3 scripts/"
        "worker_session_grace_restart_smoke_test.py "
        "verify"
    )


def verify():

    print(
        "Forge durable session grace restart test"
    )
    print(
        "========================================"
    )


    if not STATE_FILE.exists():

        raise RuntimeError(
            "No prepare state found"
        )


    state = json.loads(
        STATE_FILE.read_text()
    )


    task_id = state["taskId"]
    attempt_a_id = state["attempt1Id"]

    worker_id = state["workerId"]
    session_a = state["sessionA"]
    session_b = state["sessionB"]


    # ========================================================
    # Controller must come back while persisted grace exists.
    # ========================================================

    deadline = recovery_deadline(
        worker_id,
        session_a
    )


    if deadline is None:

        raise AssertionError(
            "Persisted recovery row disappeared "
            "during controller restart"
        )


    remaining = deadline - time.time()


    print(
        f"[VERIFY] persisted grace survived restart; "
        f"~{remaining:.1f}s remaining"
    )


    if remaining <= 2:

        raise AssertionError(
            "Recovery deadline expired before verify. "
            "Rerun prepare and restart the controller faster."
        )


    print(
        "[WAIT] waiting for worker B to reconnect"
    )


    worker_b = helpers.wait_for_session(
        worker_id,
        lambda worker:
            worker["sessionId"] == session_b
            and worker["online"]
            and worker[
                "commandStreamConnected"
            ],
        timeout=15,
    )


    print(
        "[VERIFY] session B reconnected:",
        worker_b["sessionId"],
    )


    # ========================================================
    # Critical assertion:
    # startup recovery must NOT mark A LOST while durable
    # session grace is still active.
    # ========================================================

    task_before_expiry = helpers.get(
        f"{TASKS_URL}/{task_id}"
    )


    attempts = get_attempts(
        task_id
    )


    if len(attempts) != 1:

        raise AssertionError(
            "Startup recovery created an unexpected "
            f"attempt; count={len(attempts)}"
        )


    first = attempts[0]


    if first["id"] != attempt_a_id:

        raise AssertionError(
            "Attempt 1 identity changed"
        )


    if first["status"] == "LOST":

        raise AssertionError(
            "Controller startup prematurely marked "
            "the grace-protected attempt LOST"
        )


    if task_before_expiry["status"] == "LOST":

        raise AssertionError(
            "Controller startup prematurely marked "
            "the grace-protected task LOST"
        )


    print(
        "[VERIFY] startup recovery respected "
        "persisted replay grace"
    )


    # ========================================================
    # Once the persisted deadline expires, normal
    # session-specific loss recovery + retry should happen.
    # ========================================================

    print(
        "[WAIT] persisted grace expires, "
        "then automatic retry runs on B"
    )


    completed = helpers.wait_task(
        task_id,
        "SUCCEEDED",
        timeout=max(
            30,
            int(remaining) + 25,
        ),
    )


    if (
        "grace-restart-retry-ok"
        not in (
            completed.get("stdout")
            or ""
        )
    ):

        raise AssertionError(
            "Retry output missing"
        )


    attempts = get_attempts(
        task_id
    )


    attempts = sorted(
        attempts,
        key=lambda attempt:
            attempt["attemptNumber"],
    )


    if len(attempts) != 2:

        raise AssertionError(
            "Expected exactly two attempts, "
            f"got {len(attempts)}"
        )


    first = attempts[0]
    second = attempts[1]


    if first["status"] != "LOST":

        raise AssertionError(
            "Attempt 1 should eventually become LOST, "
            f"got {first['status']}"
        )


    if first["workerSessionId"] \
            != session_a:

        raise AssertionError(
            "Attempt 1 ownership changed"
        )


    if second["status"] != "SUCCEEDED":

        raise AssertionError(
            "Attempt 2 should SUCCEED, "
            f"got {second['status']}"
        )


    if second["workerSessionId"] \
            != session_b:

        raise AssertionError(
            "Retry was not executed by session B"
        )


    # ========================================================
    # Timeline
    # ========================================================

    events = helpers.get(
        f"{TASKS_URL}/{task_id}/events"
    )

    types = [
        event["type"]
        for event in events
    ]


    for required in [
        "ATTEMPT_LOST",
        "TASK_LOST",
        "RETRY_SCHEDULED",
        "ATTEMPT_SUCCEEDED",
        "TASK_SUCCEEDED",
    ]:

        if required not in types:

            raise AssertionError(
                f"Timeline missing {required}"
            )


    if types.count(
            "ATTEMPT_LOST") != 1:

        raise AssertionError(
            "Expected exactly one ATTEMPT_LOST"
        )


    if types.count(
            "TASK_LOST") != 1:

        raise AssertionError(
            "Expected exactly one TASK_LOST"
        )


    if recovery_count(
            worker_id,
            session_a) != 0:

        raise AssertionError(
            "Recovery row was not removed after "
            "session reconciliation"
        )


    STATE_FILE.unlink(
        missing_ok=True
    )

    MARKER.unlink(
        missing_ok=True
    )


    print()
    print(
        "DURABLE SESSION GRACE RESTART PASS"
    )

    print(
        "The controller restarted while session A's "
        "replay grace was active, preserved that grace "
        "through PostgreSQL, avoided premature LOST "
        "recovery, then reconciled A and retried the task "
        "on session B after the deadline."
    )


def main():

    if len(sys.argv) != 2:

        print(
            "Usage:"
        )

        print(
            "  python3 scripts/"
            "worker_session_grace_restart_smoke_test.py "
            "prepare"
        )

        print(
            "  python3 scripts/"
            "worker_session_grace_restart_smoke_test.py "
            "verify"
        )

        return 1


    mode = sys.argv[1].lower()


    try:

        if mode == "prepare":

            prepare()

        elif mode == "verify":

            verify()

        else:

            raise ValueError(
                "Mode must be prepare or verify"
            )


        return 0


    except Exception as exc:

        print(
            f"\nFAIL: {exc}"
        )

        return 1


if __name__ == "__main__":

    sys.exit(
        main()
    )
