#!/usr/bin/env python3

import os
import signal
import sys
import time

import worker_session_fencing_smoke_test as helpers


WORKERS_URL = "http://localhost:8080/api/workers"

POLL = 0.25


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

    deadline = time.time() + timeout

    while time.time() < deadline:

        value = predicate()

        if value:
            return value

        time.sleep(POLL)

    raise AssertionError(
        f"Timed out waiting for {description}"
    )


def main():

    if not sys.platform.startswith("linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge heartbeat reconnect test"
    )
    print(
        "=============================="
    )


    worker = helpers.require_one_worker()

    worker_id = worker["id"]
    session_id = worker["sessionId"]

    pid = helpers.require_one_local_worker()


    print("Worker:", worker_id)
    print("Session:", session_id)
    print("PID:", pid)


    try:

        # ====================================================
        # Freeze the whole worker long enough for the
        # controller's health monitor to time it out.
        # ====================================================

        print(
            "\n[CHAOS] SIGSTOP worker"
        )

        os.kill(
            pid,
            signal.SIGSTOP
        )


        print(
            "[WAIT] waiting for controller timeout"
        )


        def worker_timed_out():

            current = get_worker(
                worker_id
            )

            if current is None:
                return False

            return (
                not current["online"]
                and not current[
                    "commandStreamConnected"
                ]
            )


        wait_until(
            worker_timed_out,
            "worker to become offline",
            timeout=30,
        )


        print(
            "[VERIFY] worker timed out"
        )


        # ====================================================
        # Resume the SAME process/session.
        #
        # A correct implementation should cause the worker's
        # old command stream to terminate, making its connection
        # manager reconnect that same session.
        # ====================================================

        print(
            "[RECOVER] SIGCONT worker"
        )

        os.kill(
            pid,
            signal.SIGCONT
        )


        print(
            "[WAIT] waiting for same session to recover"
        )


        def fully_reconnected():

            current = get_worker(
                worker_id
            )

            if current is None:
                return False

            return (
                current["sessionId"]
                == session_id
                and current["online"]
                and current[
                    "commandStreamConnected"
                ]
            )


        wait_until(
            fully_reconnected,
            "same worker session to regain command stream",
            timeout=20,
        )


        print(
            "[VERIFY] same session is online"
        )

        print(
            "[VERIFY] command stream reconnected"
        )


        print()
        print(
            "WORKER HEARTBEAT RECONNECT PASS"
        )


    finally:

        # Never leave the worker SIGSTOP'd after a failed test.
        try:

            os.kill(
                pid,
                signal.SIGCONT
            )

        except ProcessLookupError:

            pass


if __name__ == "__main__":

    try:

        main()

    except Exception as exc:

        print()
        print(
            f"FAIL: {exc}"
        )

        sys.exit(1)
