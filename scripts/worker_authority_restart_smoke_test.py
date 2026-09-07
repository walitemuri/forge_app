#!/usr/bin/env python3

import json
import os
import signal
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


WORKERS_URL = "http://localhost:8080/api/workers"

STATE_FILE = Path(
    "/tmp/forge_worker_authority_restart_state.json"
)

POLL = 0.25


def wait_until(
        predicate,
        description,
        timeout):

    deadline = time.time() + timeout

    while time.time() < deadline:

        value = predicate()

        if value:
            return value

        time.sleep(POLL)

    raise AssertionError(
        f"Timed out waiting for {description}"
    )


def get_worker(worker_id):

    workers = helpers.get(
        WORKERS_URL
    )

    for worker in workers:

        if worker["id"] == worker_id:
            return worker

    return None


def process_exists(pid):

    try:

        os.kill(
            pid,
            0,
        )

        return True

    except ProcessLookupError:

        return False


def prepare():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge worker authority restart test"
    )
    print(
        "==================================="
    )


    STATE_FILE.unlink(
        missing_ok=True
    )


    # ========================================================
    # Session A begins authoritative
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
    # Freeze A until controller declares it offline.
    #
    # WorkerHealthMonitor currently uses a 15 second
    # heartbeat timeout, checked every 5 seconds.
    # ========================================================

    print(
        "\n[CHAOS] SIGSTOP session A"
    )


    os.kill(
        pid_a,
        signal.SIGSTOP,
    )


    print(
        "[WAIT] waiting for controller "
        "to mark A offline"
    )


    def a_is_offline():

        worker = get_worker(
            worker_id
        )

        if worker is None:
            return False

        return not worker["online"]


    wait_until(
        a_is_offline,
        "session A to become offline",
        timeout=30,
    )


    print(
        "[VERIFY] session A is offline"
    )


    # ========================================================
    # Start B.
    #
    # Because A is no longer online, B is allowed to take
    # ownership of the stable worker ID.
    # ========================================================

    print(
        "[TAKEOVER] starting session B"
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
    # Bring A back.
    #
    # It is now the stale incarnation. The live controller
    # should continue fencing it while B owns the ID.
    # ========================================================

    print(
        "[STALE] SIGCONT session A"
    )


    os.kill(
        pid_a,
        signal.SIGCONT,
    )


    time.sleep(2)


    current = get_worker(
        worker_id
    )


    if current is None:

        raise AssertionError(
            "Worker disappeared after B takeover"
        )


    if current["sessionId"] != session_b:

        raise AssertionError(
            "Session B lost authority before "
            "controller restart"
        )


    print(
        "[VERIFY] B remains authoritative "
        "while A is stale"
    )


    # ========================================================
    # Freeze B immediately before controller restart.
    #
    # This makes the restart race deterministic:
    #
    #     A = stale, but running
    #     B = authoritative, but SIGSTOP'd
    #
    # After controller restart A gets the first chance to
    # register.
    #
    # A correct durable-authority implementation must still
    # reject A.
    # ========================================================

    print(
        "[CHAOS] SIGSTOP authoritative session B"
    )


    os.kill(
        pid_b,
        signal.SIGSTOP,
    )


    STATE_FILE.write_text(
        json.dumps(
            {
                "workerId": worker_id,
                "sessionA": session_a,
                "sessionB": session_b,
                "pidA": pid_a,
                "pidB": pid_b,
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
        "Current state:"
    )

    print(
        "  A = stale but RUNNING"
    )

    print(
        "  B = authoritative but SIGSTOP'd"
    )

    print()
    print(
        "Restart ONLY the Java controller now:"
    )

    print()
    print(
        "  sudo fuser -k 8080/tcp"
    )

    print(
        "  sudo fuser -k 50051/tcp"
    )

    print()
    print(
        "Then:"
    )

    print(
        "  cd ~/forge_app/controller"
    )

    print(
        "  ./gradlew bootRun"
    )

    print()
    print(
        "Once Spring is running:"
    )

    print()
    print(
        "  cd ~/forge_app"
    )

    print(
        "  python3 scripts/"
        "worker_authority_restart_smoke_test.py "
        "verify"
    )


def verify():

    print(
        "Forge worker authority restart test"
    )
    print(
        "==================================="
    )


    if not STATE_FILE.exists():

        raise RuntimeError(
            "No prepare state found"
        )


    state = json.loads(
        STATE_FILE.read_text()
    )


    worker_id = state["workerId"]

    session_a = state["sessionA"]
    session_b = state["sessionB"]

    pid_a = state["pidA"]
    pid_b = state["pidB"]


    test_passed = False


    try:

        # ====================================================
        # Give stale A several seconds to attempt registration.
        #
        # B is still stopped, so if A appears as authoritative,
        # controller restart has forgotten the fencing decision.
        # ====================================================

        print(
            "\n[VERIFY] checking whether stale "
            "session A reclaimed authority"
        )


        deadline = time.time() + 6


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
                    "STALE SESSION A RECLAIMED "
                    "WORKER AUTHORITY AFTER "
                    "CONTROLLER RESTART"
                )


            time.sleep(
                POLL
            )


        print(
            "[VERIFY] stale session A was rejected"
        )


        # ====================================================
        # Resume the previously authoritative B.
        # ====================================================

        print(
            "[RECOVER] SIGCONT authoritative "
            "session B"
        )


        os.kill(
            pid_b,
            signal.SIGCONT,
        )


        worker_b = helpers.wait_for_session(
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


        if worker_b["sessionId"] \
                != session_b:

            raise AssertionError(
                "Controller did not restore "
                "session B authority"
            )


        print(
            "[VERIFY] persisted authoritative "
            "session B reconnected"
        )


        test_passed = True


        print()
        print(
            "WORKER AUTHORITY RESTART PASS"
        )


    finally:

        # ====================================================
        # Always restore the machine to one live worker.
        # ====================================================

        try:

            if process_exists(
                    pid_b):

                os.kill(
                    pid_b,
                    signal.SIGCONT,
                )

        except ProcessLookupError:

            pass


        try:

            if process_exists(
                    pid_a):

                os.kill(
                    pid_a,
                    signal.SIGKILL,
                )

                print(
                    "[CLEANUP] killed stale session A"
                )

        except ProcessLookupError:

            pass


        if test_passed:

            STATE_FILE.unlink(
                missing_ok=True
            )


def main():

    if len(sys.argv) != 2:

        print(
            "Usage:"
        )

        print(
            "  python3 scripts/"
            "worker_authority_restart_smoke_test.py "
            "prepare"
        )

        print(
            "  python3 scripts/"
            "worker_authority_restart_smoke_test.py "
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

        print()
        print(
            f"FAIL: {exc}"
        )

        return 1


if __name__ == "__main__":

    sys.exit(
        main()
    )
