#!/usr/bin/env python3

import os
import stat
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


TASKS_URL = "http://localhost:8080/api/tasks"

MARKER = Path(
    "/tmp/forge_outbox_persistence_gate.marker"
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
            "This test currently requires Linux/WSL"
        )


    if os.geteuid() == 0:

        raise RuntimeError(
            "Do not run this test as root; "
            "root can bypass the permission failure"
        )


    print(
        "Forge outbox persistence gate test"
    )
    print(
        "==================================="
    )


    worker = helpers.require_one_worker()

    worker_id = worker["id"]
    session_id = worker["sessionId"]

    pid = helpers.require_one_local_worker()


    print("Worker:", worker_id)
    print("Session:", session_id)
    print("PID:", pid)


    environment = (
        helpers.read_process_environment(
            pid
        )
    )


    outbox = helpers.worker_outbox(
        worker_id,
        environment,
    )


    if not outbox.exists():

        raise AssertionError(
            f"Worker outbox does not exist: {outbox}"
        )


    print("Outbox:", outbox)


    existing_events = list(
        outbox.glob("*.event")
    )

    existing_temps = list(
        outbox.glob("*.tmp")
    )


    if existing_events or existing_temps:

        raise AssertionError(
            "Outbox must be empty before test. "
            f"events={existing_events} "
            f"temps={existing_temps}"
        )


    original_mode = stat.S_IMODE(
        outbox.stat().st_mode
    )


    MARKER.unlink(
        missing_ok=True
    )


    permissions_restored = False


    try:

        # ====================================================
        # Simulate disk persistence failure
        # ====================================================

        print(
            "\n[CHAOS] removing write permission "
            "from worker outbox"
        )


        os.chmod(
            outbox,
            0o500,
        )


        if os.access(
                outbox,
                os.W_OK):

            raise AssertionError(
                "Outbox still appears writable"
            )


        # ====================================================
        # Execute task
        # ====================================================

        task_program = (
            "from pathlib import Path; "
            f"Path({str(MARKER)!r}).write_text('done'); "
            "print('durability-gate-ok')"
        )


        print(
            "[CREATE] submitting task"
        )


        task = helpers.post(
            TASKS_URL,
            {
                "command": "python3",
                "arguments": [
                    "-c",
                    task_program,
                ],
                "maxAttempts": 1,
                "timeoutSeconds": 30,
            },
        )


        task_id = task["id"]

        print("Task:", task_id)


        # ====================================================
        # Prove the task physically executed
        # ====================================================

        print(
            "[WAIT] waiting for physical execution"
        )


        wait_until(
            lambda: MARKER.exists(),
            "task execution marker",
            timeout=10,
        )


        print(
            "[VERIFY] process executed on worker"
        )


        # Give both TaskAccepted and TaskResult enough
        # time to attempt persistence and retry.
        time.sleep(2.5)


        # ====================================================
        # Controller must NOT know execution completed
        # ====================================================

        task_before = helpers.get(
            f"{TASKS_URL}/{task_id}"
        )


        print(
            "[VERIFY] controller status while "
            "persistence is unavailable:",
            task_before["status"],
        )


        if task_before["status"] != "DISPATCHED":

            raise AssertionError(
                "Expected controller task to remain "
                "DISPATCHED while events are undurable, "
                f"got {task_before['status']}"
            )


        attempts = get_attempts(
            task_id
        )


        if len(attempts) != 1:

            raise AssertionError(
                "Expected exactly one attempt, "
                f"got {len(attempts)}"
            )


        if attempts[0]["status"] != "DISPATCHED":

            raise AssertionError(
                "Expected attempt to remain DISPATCHED, "
                f"got {attempts[0]['status']}"
            )


        # Persistence failed before visibility, so there
        # must not be a durable .event file yet.
        event_files = list(
            outbox.glob("*.event")
        )


        if event_files:

            raise AssertionError(
                "Found durable event despite forced "
                f"persistence failure: {event_files}"
            )


        print(
            "[VERIFY] undurable events were withheld "
            "from controller"
        )


        # ====================================================
        # Restore storage
        # ====================================================

        print(
            "[RECOVER] restoring outbox write permission"
        )


        os.chmod(
            outbox,
            original_mode,
        )

        permissions_restored = True


        # ====================================================
        # Persistence retry should unblock delivery
        # ====================================================

        print(
            "[WAIT] waiting for persistence retry "
            "and event delivery"
        )


        completed = helpers.wait_task(
            task_id,
            "SUCCEEDED",
            timeout=15,
        )


        if (
            "durability-gate-ok"
            not in (
                completed.get("stdout")
                or ""
            )
        ):

            raise AssertionError(
                "Completed task output missing"
            )


        attempts = get_attempts(
            task_id
        )


        if len(attempts) != 1:

            raise AssertionError(
                "Expected exactly one attempt after "
                f"recovery, got {len(attempts)}"
            )


        if attempts[0]["status"] \
                != "SUCCEEDED":

            raise AssertionError(
                "Attempt did not succeed after "
                "persistence recovered"
            )


        # ====================================================
        # ACK should drain disk outbox again
        # ====================================================

        def outbox_drained():

            return (
                not list(
                    outbox.glob("*.event")
                )
                and not list(
                    outbox.glob("*.tmp")
                )
            )


        wait_until(
            outbox_drained,
            "outbox to drain after ACK",
            timeout=10,
        )


        print(
            "[VERIFY] durable events delivered "
            "and ACKed"
        )


        print()
        print(
            "OUTBOX PERSISTENCE GATE PASS"
        )

        print(
            "The worker executed the task while "
            "storage was unavailable, but Forge "
            "withheld TaskAccepted and TaskResult "
            "until their durable outbox copies "
            "could be created."
        )


    finally:

        if not permissions_restored:

            try:

                os.chmod(
                    outbox,
                    original_mode,
                )

                print(
                    "\n[CLEANUP] restored outbox "
                    "permissions"
                )

            except Exception as cleanup_error:

                print(
                    "\nWARNING: unable to restore "
                    f"outbox permissions: {cleanup_error}"
                )


        MARKER.unlink(
            missing_ok=True
        )


if __name__ == "__main__":

    try:

        main()

    except Exception as exc:

        print(
            f"\nFAIL: {exc}"
        )

        sys.exit(1)
