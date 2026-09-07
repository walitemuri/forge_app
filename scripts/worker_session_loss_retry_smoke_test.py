#!/usr/bin/env python3

import os
import signal
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


TASKS_URL = "http://localhost:8080/api/tasks"

MARKER = Path(
    "/tmp/forge_session_loss_retry.marker"
)

POLL = 0.20


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


def get_attempts(task_id):

    return helpers.get(
        f"{TASKS_URL}/{task_id}/attempts"
    )


def main():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge worker session loss retry test"
    )
    print(
        "===================================="
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

    pid_a = (
        helpers.require_one_local_worker()
    )


    print("Worker:", worker_id)
    print("Session A:", session_a)
    print("PID A:", pid_a)


    environment = (
        helpers.read_process_environment(
            pid_a
        )
    )


    # ========================================================
    # Task
    # ========================================================

    # First execution creates the marker and waits while its
    # worker parent exists. After SIGKILL, Linux reparents it
    # and it exits. Retry sees the marker and succeeds.

    task_program = f"""
import os
import time
from pathlib import Path

marker = Path({str(MARKER)!r})

if marker.exists():
    print("session-loss-retry-ok")
else:
    marker.touch()
    parent = os.getppid()

    while os.getppid() == parent:
        time.sleep(0.2)
"""


    print("\n[CREATE] submitting retryable task")


    task = helpers.post(
        TASKS_URL,
        {
            "command": "python3",
            "arguments": [
                "-c",
                task_program,
            ],
            "maxAttempts": 2,
            "timeoutSeconds": 60,
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
            "Expected exactly one attempt "
            f"before crash, got {len(attempts)}"
        )


    attempt_a = attempts[0]

    attempt_a_id = attempt_a["id"]


    if attempt_a["workerSessionId"] \
            != session_a:

        raise AssertionError(
            "Attempt 1 was not owned by "
            "session A"
        )


    print(
        "Attempt 1:",
        attempt_a_id,
    )


    # ========================================================
    # Kill A before it can produce TaskResult
    # ========================================================

    print(
        "[CRASH] SIGKILL session A "
        "before result generation"
    )


    os.kill(
        pid_a,
        signal.SIGKILL,
    )


    # ========================================================
    # Start replacement B
    # ========================================================

    print(
        "[RESTART] starting replacement worker"
    )


    process_b = helpers.launch_worker(
        environment
    )

    pid_b = process_b.pid

    print("PID B:", pid_b)


    print(
        "[WAIT] waiting for session B takeover"
    )


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


    session_b = worker_b[
        "sessionId"
    ]

    print(
        "Session B:",
        session_b,
    )


    # ========================================================
    # Grace-period recovery
    # ========================================================

    print(
        "[WAIT] replay grace expires "
        "and attempt 1 becomes LOST"
    )


    def first_attempt_lost():

        attempts_now = get_attempts(
            task_id
        )

        if not attempts_now:
            return False

        first = attempts_now[0]

        if first["status"] == "LOST":
            return first

        return False


    lost_attempt = wait_until(
        first_attempt_lost,
        "attempt 1 to become LOST",
        timeout=20,
    )


    if lost_attempt[
            "workerSessionId"] != session_a:

        raise AssertionError(
            "LOST attempt ownership changed"
        )


    print(
        "[VERIFY] attempt 1 LOST"
    )


    # ========================================================
    # Automatic retry on B
    # ========================================================

    print(
        "[WAIT] automatic retry on session B"
    )


    completed = helpers.wait_task(
        task_id,
        "SUCCEEDED",
        timeout=20,
    )


    if (
        "session-loss-retry-ok"
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


    if len(attempts) != 2:

        raise AssertionError(
            "Expected exactly two attempts, "
            f"got {len(attempts)}"
        )


    first = attempts[0]
    second = attempts[1]


    if first["status"] != "LOST":

        raise AssertionError(
            "Attempt 1 expected LOST, got "
            f"{first['status']}"
        )


    if first["workerSessionId"] \
            != session_a:

        raise AssertionError(
            "Attempt 1 session mismatch"
        )


    if second["status"] != "SUCCEEDED":

        raise AssertionError(
            "Attempt 2 expected SUCCEEDED, got "
            f"{second['status']}"
        )


    if second["workerSessionId"] \
            != session_b:

        raise AssertionError(
            "Attempt 2 was not owned by "
            "session B"
        )


    # ========================================================
    # Timeline
    # ========================================================

    events = helpers.get(
        f"{TASKS_URL}/{task_id}/events"
    )

    event_types = [
        event["type"]
        for event in events
    ]


    required_events = [
        "ATTEMPT_LOST",
        "TASK_LOST",
        "RETRY_SCHEDULED",
        "ATTEMPT_SUCCEEDED",
        "TASK_SUCCEEDED",
    ]


    for event_type in required_events:

        if event_type not in event_types:

            raise AssertionError(
                "Timeline missing "
                f"{event_type}"
            )


    print()
    print(
        "WORKER SESSION LOSS RETRY PASS"
    )

    print(
        "Attempt 1 became LOST only after "
        "the replay grace period, Forge "
        "retried it on the replacement "
        "session, and attempt 2 succeeded."
    )

    print()
    print(
        "Session A:",
        session_a,
    )

    print(
        "Session B:",
        session_b,
    )

    print(
        "Replacement worker remains "
        f"running as PID {pid_b}"
    )


    MARKER.unlink(
        missing_ok=True
    )


if __name__ == "__main__":

    try:
        main()

    except Exception as exc:

        MARKER.unlink(
            missing_ok=True
        )

        print(
            f"\nFAIL: {exc}"
        )

        sys.exit(1)
