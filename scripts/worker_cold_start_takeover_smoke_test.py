#!/usr/bin/env python3

import json
import os
import signal
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


STATE_FILE = Path(
    "/tmp/forge_worker_cold_start_takeover.json"
)

WORKERS_URL = "http://localhost:8080/api/workers"

POLL = 0.25


def process_exists(pid):

    try:
        os.kill(pid, 0)
        return True

    except ProcessLookupError:
        return False


def get_worker(worker_id):

    for worker in helpers.get(
            WORKERS_URL):

        if worker["id"] == worker_id:
            return worker

    return None


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


def prepare():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    if STATE_FILE.exists():

        raise RuntimeError(
            "Cold-start takeover state already exists at "
            f"{STATE_FILE}. Run verify or remove it explicitly "
            "before starting another prepare phase."
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
        "Forge cold-start takeover test"
    )
    print(
        "================================"
    )

    print("Worker:", worker_id)
    print("Session A:", session_a)
    print("PID A:", pid_a)


    print(
        "\n[CHAOS] SIGSTOP authoritative worker A"
    )

    os.kill(
        pid_a,
        signal.SIGSTOP
    )


    STATE_FILE.write_text(
        json.dumps(
            {
                "workerId": worker_id,
                "sessionA": session_a,
                "pidA": pid_a,
                "environment": environment,
            }
        )
    )


    print()
    print("PREPARE PASS")
    print()
    print(
        "Restart ONLY the Java controller now."
    )
    print()
    print(
        "Then run:"
    )
    print(
        "python3 scripts/"
        "worker_cold_start_takeover_smoke_test.py verify"
    )


def verify():

    state = json.loads(
        STATE_FILE.read_text()
    )

    worker_id = state["workerId"]
    session_a = state["sessionA"]
    pid_a = state["pidA"]
    environment = state["environment"]


    print(
        "Forge cold-start takeover verification"
    )
    print(
        "======================================"
    )

    print("Persisted session A:", session_a)


    process_c = None
    replacement_ready = False
    failure = None


    try:

        current = get_worker(
            worker_id
        )


        if (
            current is not None
            and current["online"]
        ):

            raise AssertionError(
                "Expected no live worker immediately "
                "after controller restart"
            )


        print(
            "\n[START] launching fresh worker C"
        )


        process_c = (
            helpers.launch_worker(
                environment
            )
        )


        print(
            "[WAIT] fresh session should eventually "
            "replace absent persisted authority"
        )


        def fresh_worker_connected():

            worker = get_worker(
                worker_id
            )

            if worker is None:
                return None

            if (
                worker["sessionId"]
                != session_a
                and worker["online"]
                and worker[
                    "commandStreamConnected"
                ]
            ):

                return worker

            return None


        worker_c = wait_until(
            fresh_worker_connected,
            "fresh worker to take over cold authority",
            timeout=20,
        )


        replacement_ready = True


        print(
            "[VERIFY] fresh session accepted:",
            worker_c["sessionId"],
        )


        print()
        print(
            "WORKER COLD START TAKEOVER PASS"
        )


    except Exception as exc:

        failure = exc


    finally:

        if replacement_ready:

            # A is now stale forever.
            try:

                if process_exists(
                        pid_a):

                    os.kill(
                        pid_a,
                        signal.SIGKILL
                    )

                    print(
                        "[CLEANUP] killed stale session A"
                    )

            except ProcessLookupError:
                pass

        else:

            # Current pre-fix behavior lands here.
            if process_c is not None:

                try:

                    if process_c.poll() is None:

                        os.kill(
                            process_c.pid,
                            signal.SIGKILL
                        )

                        print(
                            "[CLEANUP] killed rejected worker C"
                        )

                except ProcessLookupError:
                    pass


            # Restore the original authoritative worker.
            try:

                if process_exists(
                        pid_a):

                    os.kill(
                        pid_a,
                        signal.SIGCONT
                    )

                    print(
                        "[CLEANUP] resumed session A"
                    )


                    wait_until(
                        lambda:
                            (
                                (worker := get_worker(
                                    worker_id
                                ))
                                is not None
                                and worker["sessionId"]
                                    == session_a
                                and worker["online"]
                                and worker[
                                    "commandStreamConnected"
                                ]
                            ),
                        "session A to reconnect",
                        timeout=20,
                    )

            except Exception as cleanup_exc:

                print(
                    "[CLEANUP WARNING]",
                    cleanup_exc,
                )


        STATE_FILE.unlink(
            missing_ok=True
        )


    if failure is not None:

        raise failure


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
            "worker_cold_start_takeover_smoke_test.py "
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
