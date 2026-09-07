#!/usr/bin/env python3

import os
import shutil
import signal
import stat
import sys
import time
from pathlib import Path

import worker_session_fencing_smoke_test as helpers


TASKS_URL = "http://localhost:8080/api/tasks"

TEMP_ROOT = Path(
    f"/tmp/forge_ordering_gate_{os.getpid()}"
)

START_MARKER = TEMP_ROOT / "started"
RELEASE_MARKER = TEMP_ROOT / "release"

POLL = 0.02


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


def read_log():

    if not helpers.SECONDARY_LOG.exists():
        return ""

    return helpers.SECONDARY_LOG.read_text(
        errors="replace"
    )


def main():

    if not sys.platform.startswith("linux"):
        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge outbox ordering gate test"
    )
    print(
        "==============================="
    )


    TEMP_ROOT.mkdir(
        parents=True,
        exist_ok=False,
    )


    original_worker = (
        helpers.require_one_worker()
    )

    worker_id = original_worker["id"]
    original_session = (
        original_worker["sessionId"]
    )

    original_pid = (
        helpers.require_one_local_worker()
    )

    original_environment = (
        helpers.read_process_environment(
            original_pid
        )
    )


    print("Worker:", worker_id)
    print("Original session:", original_session)
    print("Original PID:", original_pid)


    test_environment = dict(
        original_environment
    )

    test_environment[
        "FORGE_OUTBOX_DIR"
    ] = str(
        TEMP_ROOT / "outbox"
    )


    test_process = None
    cleanup_process = None
    outbox_mode = None


    try:

        # ====================================================
        # Replace current worker with isolated test worker
        # ====================================================

        print(
            "\n[SETUP] replacing worker with "
            "isolated-outbox session"
        )


        os.kill(
            original_pid,
            signal.SIGKILL,
        )


        test_process = helpers.launch_worker(
            test_environment
        )


        test_pid = test_process.pid


        test_worker = helpers.wait_for_session(
            worker_id,
            lambda worker:
                worker["sessionId"]
                != original_session
                and worker["online"]
                and worker[
                    "commandStreamConnected"
                ],
            timeout=20,
        )


        test_session = (
            test_worker["sessionId"]
        )


        print("Test session:", test_session)
        print("Test PID:", test_pid)


        outbox = helpers.worker_outbox(
            worker_id,
            test_environment,
        )


        wait_until(
            lambda: outbox.exists(),
            "test outbox directory",
            timeout=5,
        )


        outbox_mode = stat.S_IMODE(
            outbox.stat().st_mode
        )


        if list(outbox.glob("*.event")):
            raise AssertionError(
                "Test outbox was not empty"
            )


        # ====================================================
        # Prevent TaskAccepted persistence
        # ====================================================

        print(
            "[CHAOS] disabling outbox writes"
        )


        os.chmod(
            outbox,
            0o500,
        )


        # ====================================================
        # Task waits for us before returning a result
        # ====================================================

        task_program = f"""
import time
from pathlib import Path

started = Path({str(START_MARKER)!r})
release = Path({str(RELEASE_MARKER)!r})

started.touch()

while not release.exists():
    time.sleep(0.02)

print("ordering-gate-ok")
"""


        print(
            "[CREATE] submitting controlled task"
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


        wait_until(
            lambda: START_MARKER.exists(),
            "physical task start",
            timeout=10,
        )


        attempts = helpers.get(
            f"{TASKS_URL}/{task_id}/attempts"
        )


        if len(attempts) != 1:
            raise AssertionError(
                "Expected exactly one attempt, "
                f"got {len(attempts)}"
            )


        attempt_id = attempts[0]["id"]

        print("Attempt:", attempt_id)


        accepted_event_id = (
            f"{attempt_id}:accepted"
        )

        accepted_failure_text = (
            "persistence retry failed for "
            + accepted_event_id
        )


        print(
            "[WAIT] confirming Accepted "
            "persistence retry failed"
        )


        wait_until(
            lambda:
                accepted_failure_text
                in read_log(),
            "Accepted persistence failure",
            timeout=5,
        )


        # ====================================================
        # Restore disk, then let task complete immediately
        #
        # The persistence thread has just failed and enters
        # its ~1 second retry sleep. Result should therefore
        # reach durable storage before Accepted retries.
        # ====================================================

        print(
            "[RECOVER] restoring writes and "
            "releasing task"
        )


        os.chmod(
            outbox,
            outbox_mode,
        )


        RELEASE_MARKER.touch()


        accepted_file = (
            outbox
            / f"{attempt_id}_accepted.event"
        )

        result_file = (
            outbox
            / f"{attempt_id}_result.event"
        )


        print(
            "[WAIT] waiting for Result to "
            "become durable first"
        )


        wait_until(
            lambda: result_file.exists(),
            "durable Result event",
            timeout=0.75,
        )


        if accepted_file.exists():
            raise AssertionError(
                "Accepted became durable before "
                "the ordering race could be observed"
            )


        print(
            "[VERIFY] Result is durable while "
            "Accepted is still undurable"
        )


        # ====================================================
        # Result must still be withheld from controller
        # ====================================================

        controller_task = helpers.get(
            f"{TASKS_URL}/{task_id}"
        )


        if controller_task["status"] \
                != "DISPATCHED":

            raise AssertionError(
                "Result overtook Accepted: expected "
                "controller status DISPATCHED, got "
                f"{controller_task['status']}"
            )


        attempts = helpers.get(
            f"{TASKS_URL}/{task_id}/attempts"
        )


        if attempts[0]["status"] \
                != "DISPATCHED":

            raise AssertionError(
                "Result overtook Accepted at attempt "
                "level: expected DISPATCHED, got "
                f"{attempts[0]['status']}"
            )


        print(
            "[VERIFY] durable Result was withheld "
            "behind undurable Accepted"
        )


        # ====================================================
        # Accepted retry should unblock ordered delivery
        # ====================================================

        print(
            "[WAIT] Accepted recovery should "
            "release the ordered prefix"
        )


        completed = helpers.wait_task(
            task_id,
            "SUCCEEDED",
            timeout=10,
        )


        if (
            "ordering-gate-ok"
            not in (
                completed.get("stdout")
                or ""
            )
        ):
            raise AssertionError(
                "Final output missing"
            )


        events = helpers.get(
            f"{TASKS_URL}/{task_id}/events"
        )

        event_types = [
            event["type"]
            for event in events
        ]


        if "ATTEMPT_RUNNING" \
                not in event_types:

            raise AssertionError(
                "Timeline missing ATTEMPT_RUNNING"
            )


        if "ATTEMPT_SUCCEEDED" \
                not in event_types:

            raise AssertionError(
                "Timeline missing ATTEMPT_SUCCEEDED"
            )


        if (
            event_types.index(
                "ATTEMPT_RUNNING"
            )
            >
            event_types.index(
                "ATTEMPT_SUCCEEDED"
            )
        ):
            raise AssertionError(
                "Attempt success appeared before "
                "attempt running"
            )


        print(
            "[VERIFY] controller observed "
            "Accepted before Result"
        )


        print()
        print(
            "OUTBOX ORDERING GATE PASS"
        )

        print(
            "TaskResult became durable while "
            "TaskAccepted was still undurable, "
            "but Forge prevented the Result from "
            "overtaking the queue head."
        )


    finally:

        if outbox_mode is not None:

            try:
                outbox = helpers.worker_outbox(
                    worker_id,
                    test_environment,
                )

                if outbox.exists():
                    os.chmod(
                        outbox,
                        outbox_mode,
                    )

            except Exception:
                pass


        if test_process is not None:

            try:

                if test_process.poll() is None:

                    os.kill(
                        test_process.pid,
                        signal.SIGKILL,
                    )

            except ProcessLookupError:
                pass


        # ====================================================
        # Restore normal worker environment
        # ====================================================

        try:

            print(
                "\n[CLEANUP] restoring normal worker"
            )


            cleanup_process = (
                helpers.launch_worker(
                    original_environment
                )
            )


            helpers.wait_for_session(
                worker_id,
                lambda worker:
                    worker["online"]
                    and worker[
                        "commandStreamConnected"
                    ]
                    and worker["sessionId"]
                    != original_session,
                timeout=20,
            )


            print(
                "Replacement worker remains running "
                f"as PID {cleanup_process.pid}"
            )


        finally:

            START_MARKER.unlink(
                missing_ok=True
            )

            RELEASE_MARKER.unlink(
                missing_ok=True
            )


            try:

                shutil.rmtree(
                    TEMP_ROOT
                )

            except Exception:
                pass


if __name__ == "__main__":

    try:
        main()

    except Exception as exc:

        print(
            f"\nFAIL: {exc}"
        )

        sys.exit(1)
