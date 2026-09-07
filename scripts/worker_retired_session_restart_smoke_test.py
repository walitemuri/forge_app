#!/usr/bin/env python3

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


STATE_FILE = Path(
    "/tmp/forge_worker_retired_session_restart.json"
)

WORKERS_URL = "http://localhost:8080/api/workers"

POLL = 0.25


def db_environment():

    environment = dict(os.environ)
    environment["PGPASSWORD"] = "forge"

    return environment


def db_scalar(sql):

    result = subprocess.run(
        [
            "psql",
            "-h", "127.0.0.1",
            "-p", "5433",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U", "forge",
            "-d", "forge",
            "-c", sql,
        ],
        text=True,
        capture_output=True,
        env=db_environment(),
    )

    if result.returncode != 0:

        raise RuntimeError(
            "Database command failed:\n"
            + result.stderr
        )

    lines = [
        line.strip()
        for line in result.stdout.splitlines()
        if line.strip()
    ]

    return (
        lines[0]
        if lines
        else None
    )


def sql_escape(value):

    return value.replace(
        "'",
        "''"
    )


def authority_session(worker_id):

    worker = sql_escape(worker_id)

    return db_scalar(
        f"""
        SELECT session_id
        FROM worker_authorities
        WHERE worker_id = '{worker}';
        """
    )


def retired_count(
        worker_id,
        session_id):

    worker = sql_escape(worker_id)
    session = sql_escape(session_id)

    value = db_scalar(
        f"""
        SELECT COUNT(*)
        FROM retired_worker_sessions
        WHERE worker_id = '{worker}'
          AND session_id = '{session}';
        """
    )

    return int(value or "0")


def get_worker(worker_id):

    workers = helpers.get(
        WORKERS_URL
    )

    for worker in workers:

        if worker["id"] == worker_id:
            return worker

    return None


def wait_until(
        predicate,
        description,
        timeout):

    deadline = (
        time.time()
        + timeout
    )

    while time.time() < deadline:

        value = predicate()

        if value:
            return value

        time.sleep(POLL)

    raise AssertionError(
        f"Timed out waiting for {description}"
    )


def process_exists(pid):

    try:

        os.kill(pid, 0)
        return True

    except ProcessLookupError:

        return False


def prepare():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    if STATE_FILE.exists():

        raise RuntimeError(
            "Existing test state found at "
            f"{STATE_FILE}. Run verify or remove "
            "it before starting again."
        )


    worker_a = (
        helpers.require_one_worker()
    )

    pid_a = (
        helpers.require_one_local_worker()
    )

    worker_id = worker_a["id"]
    session_a = worker_a["sessionId"]

    environment = (
        helpers.read_process_environment(
            pid_a
        )
    )


    print(
        "Forge retired-session restart test"
    )
    print(
        "=================================="
    )

    print("Worker:", worker_id)
    print("Session A:", session_a)
    print("PID A:", pid_a)


    # ========================================================
    # Make A unavailable.
    # ========================================================

    print(
        "\n[CHAOS] SIGSTOP session A"
    )

    os.kill(
        pid_a,
        signal.SIGSTOP
    )


    print(
        "[WAIT] waiting for A to become offline"
    )


    wait_until(
        lambda:
            (
                (worker := get_worker(
                    worker_id
                ))
                is not None
                and not worker["online"]
            ),
        "session A to become offline",
        timeout=30,
    )


    print(
        "[VERIFY] A is offline"
    )


    # ========================================================
    # Start B.
    #
    # This ordinary live-controller takeover should:
    #
    #     retire A
    #     authority A -> B
    # ========================================================

    print(
        "[TAKEOVER] starting replacement B"
    )

    process_b = (
        helpers.launch_worker(
            environment
        )
    )


    worker_b = (
        helpers.wait_for_session(
            worker_id,
            lambda worker:
                worker["sessionId"]
                != session_a
                and worker["online"]
                and worker[
                    "commandStreamConnected"
                ],
            timeout=20,
        )
    )


    session_b = (
        worker_b["sessionId"]
    )

    pid_b = (
        process_b.pid
    )


    print(
        "[VERIFY] session B authoritative:",
        session_b,
    )


    if authority_session(
            worker_id) != session_b:

        raise AssertionError(
            "Durable authority did not move to B"
        )


    if retired_count(
            worker_id,
            session_a) != 1:

        raise AssertionError(
            "Session A was not durably retired"
        )


    print(
        "[VERIFY] session A durably retired"
    )


    # ========================================================
    # Freeze B too.
    #
    # After controller restart there will be no in-memory
    # worker. PostgreSQL alone must protect B's authority.
    # ========================================================

    print(
        "[CHAOS] SIGSTOP authoritative session B"
    )

    os.kill(
        pid_b,
        signal.SIGSTOP
    )


    STATE_FILE.write_text(
        json.dumps(
            {
                "workerId":
                    worker_id,

                "sessionA":
                    session_a,

                "sessionB":
                    session_b,

                "pidA":
                    pid_a,

                "pidB":
                    pid_b,
            }
        )
    )


    print()
    print(
        "PREPARE PASS"
    )
    print()
    print(
        "Restart ONLY the Java controller."
    )
    print()
    print(
        "Then run:"
    )
    print(
        "python3 scripts/"
        "worker_retired_session_restart_smoke_test.py "
        "verify"
    )


def verify():

    state = json.loads(
        STATE_FILE.read_text()
    )

    worker_id = state["workerId"]
    session_a = state["sessionA"]
    session_b = state["sessionB"]
    pid_a = state["pidA"]
    pid_b = state["pidB"]


    print(
        "Forge retired-session restart verification"
    )
    print(
        "=========================================="
    )

    print("Retired session A:", session_a)
    print("Authority session B:", session_b)


    success = False


    try:

        if authority_session(
                worker_id) != session_b:

            raise AssertionError(
                "Controller restart changed durable authority"
            )


        if retired_count(
                worker_id,
                session_a) != 1:

            raise AssertionError(
                "Retired-session history disappeared "
                "across controller restart"
            )


        # ====================================================
        # Wait beyond cold-start grace.
        #
        # This is critical. Once grace is gone, an UNKNOWN
        # session could legitimately take over.
        #
        # A must still be rejected because A is not unknown:
        # it is permanently retired.
        # ====================================================

        print(
            "\n[WAIT] waiting beyond cold-start grace"
        )

        time.sleep(12)


        print(
            "[CHAOS] SIGCONT retired session A"
        )

        os.kill(
            pid_a,
            signal.SIGCONT
        )


        # Give A several registration retries.
        #
        # It must NEVER become the registry authority.
        deadline = (
            time.time()
            + 8
        )


        while time.time() < deadline:

            worker = get_worker(
                worker_id
            )


            if (
                worker is not None
                and worker["sessionId"]
                    == session_a
                and worker["online"]
                and worker[
                    "commandStreamConnected"
                ]
            ):

                raise AssertionError(
                    "RETIRED SESSION A RECLAIMED "
                    "WORKER AUTHORITY"
                )


            if authority_session(
                    worker_id) != session_b:

                raise AssertionError(
                    "Retired A changed durable authority"
                )


            time.sleep(POLL)


        print(
            "[VERIFY] retired A remained fenced "
            "after cold-start grace"
        )


        if retired_count(
                worker_id,
                session_a) != 1:

            raise AssertionError(
                "Retired-session history was removed"
            )


        # ====================================================
        # Restore actual authority B.
        # ====================================================

        print(
            "[RECOVER] SIGCONT authoritative session B"
        )

        os.kill(
            pid_b,
            signal.SIGCONT
        )


        worker_b = (
            helpers.wait_for_session(
                worker_id,
                lambda worker:
                    worker["sessionId"]
                    == session_b
                    and worker["online"]
                    and worker[
                        "commandStreamConnected"
                    ],
                timeout=20,
            )
        )


        print(
            "[VERIFY] authoritative B reconnected:",
            worker_b["sessionId"],
        )


        if authority_session(
                worker_id) != session_b:

            raise AssertionError(
                "Durable authority no longer matches B"
            )


        # A is permanently stale.
        if process_exists(
                pid_a):

            os.kill(
                pid_a,
                signal.SIGKILL
            )

            print(
                "[CLEANUP] killed retired session A"
            )


        success = True


        print()
        print(
            "WORKER RETIRED SESSION RESTART PASS"
        )


    finally:

        # Never leave B stopped.
        try:

            if process_exists(
                    pid_b):

                os.kill(
                    pid_b,
                    signal.SIGCONT
                )

        except ProcessLookupError:

            pass


        # On failure, also resume A so we do not leave a
        # SIGSTOP'd process hidden in the machine.
        if not success:

            try:

                if process_exists(
                        pid_a):

                    os.kill(
                        pid_a,
                        signal.SIGCONT
                    )

            except ProcessLookupError:

                pass


        if success:

            STATE_FILE.unlink(
                missing_ok=True
            )


def main():

    mode = (
        sys.argv[1]
        if len(sys.argv) > 1
        else "prepare"
    )


    if mode == "prepare":

        prepare()

    elif mode == "verify":

        verify()

    else:

        raise RuntimeError(
            "Usage: "
            "worker_retired_session_restart_smoke_test.py "
            "[prepare|verify]"
        )


if __name__ == "__main__":

    try:

        main()

    except Exception as exc:

        print()
        print(
            f"FAIL: {exc}"
        )

        sys.exit(1)
