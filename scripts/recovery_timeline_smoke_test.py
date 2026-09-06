#!/usr/bin/env python3

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


BASE = "http://localhost:8080"
TASK_URL = f"{BASE}/api/tasks"
WORKER_URL = f"{BASE}/api/workers"

STATE_FILE = Path(
    "/tmp/forge_recovery_timeline_state.json"
)

POLL_INTERVAL = 0.25


# ============================================================
# HTTP
# ============================================================

def request_json(method, url, body=None):
    data = None

    if body is not None:
        data = json.dumps(
            body
        ).encode("utf-8")

    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(
                request,
                timeout=10) as response:

            text = (
                response
                .read()
                .decode("utf-8")
            )

            if not text:
                return None

            return json.loads(text)

    except urllib.error.HTTPError as exc:
        body = (
            exc.read()
            .decode("utf-8")
        )

        raise RuntimeError(
            f"{method} {url} failed: "
            f"HTTP {exc.code}: {body}"
        )


def get(url):
    return request_json(
        "GET",
        url,
    )


def post(url, body):
    return request_json(
        "POST",
        url,
        body,
    )


# ============================================================
# Forge helpers
# ============================================================

def get_task(task_id):
    return get(
        f"{TASK_URL}/{task_id}"
    )


def get_attempts(task_id):
    return get(
        f"{TASK_URL}/{task_id}/attempts"
    )


def get_events(task_id):
    return get(
        f"{TASK_URL}/{task_id}/events"
    )


def wait_for_status(
        task_id,
        expected,
        timeout=45):

    deadline = (
        time.time()
        + timeout
    )

    while time.time() < deadline:

        task = get_task(
            task_id
        )

        if task["status"] == expected:
            return task

        time.sleep(
            POLL_INTERVAL
        )

    task = get_task(
        task_id
    )

    raise AssertionError(
        f"Task {task_id}: "
        f"expected {expected}, "
        f"got {task['status']}"
    )


def wait_for_worker(
        timeout=20):

    deadline = (
        time.time()
        + timeout
    )

    while time.time() < deadline:

        workers = get(
            WORKER_URL
        )

        connected = [
            worker
            for worker in workers
            if worker["online"]
            and worker[
                "commandStreamConnected"
            ]
        ]

        if connected:
            return connected[0]

        time.sleep(
            POLL_INTERVAL
        )

    raise AssertionError(
        "No connected Forge worker"
    )


def event_types(events):

    return [
        event["type"]
        for event in events
    ]


def assert_subsequence(
        actual,
        expected):

    position = 0

    for item in actual:

        if (
            position < len(expected)
            and item
            == expected[position]
        ):
            position += 1

    if position != len(expected):

        raise AssertionError(
            "Recovery timeline ordering "
            "is incorrect.\n\n"
            f"Expected subsequence:\n"
            f"{expected}\n\n"
            f"Actual:\n"
            f"{actual}"
        )


# ============================================================
# Prepare
# ============================================================

def prepare():

    print(
        "Forge recovery timeline test"
    )
    print(
        "============================"
    )

    worker = wait_for_worker()

    print(
        "Worker:",
        worker["id"],
    )

    print(
        "\n[PREPARE] creating "
        "long-running retryable task"
    )

    task = post(
        TASK_URL,
        {
            "command": "python3",
            "arguments": [
                "-c",
                (
                    "import time; "
                    "print("
                    "'RECOVERY ATTEMPT START', "
                    "flush=True); "
                    "time.sleep(12); "
                    "print("
                    "'RECOVERY ATTEMPT DONE', "
                    "flush=True)"
                ),
            ],
            "maxAttempts": 2,
            "timeoutSeconds": 30,
        },
    )

    task_id = task["id"]

    print(
        "Task:",
        task_id,
    )

    print(
        "[WAIT] waiting for RUNNING"
    )

    wait_for_status(
        task_id,
        "RUNNING",
    )

    attempts = get_attempts(
        task_id
    )

    if len(attempts) != 1:

        raise AssertionError(
            "Expected exactly one "
            "attempt before restart"
        )

    attempt = attempts[0]

    if attempt["status"] != "RUNNING":

        raise AssertionError(
            "Attempt 1 should be RUNNING, "
            f"got {attempt['status']}"
        )

    STATE_FILE.write_text(
        json.dumps(
            {
                "taskId": task_id,
                "attempt1Id":
                    attempt["id"],
            },
            indent=2,
        )
    )

    print(
        "[VERIFY] task and attempt "
        "are RUNNING"
    )

    print()
    print(
        "PREPARE PASS"
    )

    print()
    print(
        "Now IMMEDIATELY:"
    )

    print(
        "  1. Ctrl+C the Java controller"
    )

    print(
        "  2. LEAVE THE C++ WORKER RUNNING"
    )

    print(
        "  3. Restart controller:"
    )

    print(
        "     cd ~/forge_app/controller"
    )

    print(
        "     ./gradlew bootRun"
    )

    print(
        "  4. Once Spring is running:"
    )

    print(
        "     cd ~/forge_app"
    )

    print(
        "     python3 "
        "scripts/"
        "recovery_timeline_smoke_test.py "
        "verify"
    )


# ============================================================
# Verify
# ============================================================

def verify():

    print(
        "Forge recovery timeline test"
    )
    print(
        "============================"
    )

    if not STATE_FILE.exists():

        raise RuntimeError(
            "No prepare state found. "
            "Run prepare first."
        )

    state = json.loads(
        STATE_FILE.read_text()
    )

    task_id = (
        state["taskId"]
    )

    attempt1_id = (
        state["attempt1Id"]
    )

    print(
        "\n[VERIFY] waiting for worker "
        "to reconnect"
    )

    worker = wait_for_worker(
        timeout=20
    )

    print(
        "Worker reconnected:",
        worker["id"],
    )

    print(
        "[WAIT] waiting for recovered "
        "task to retry and succeed"
    )

    completed = wait_for_status(
        task_id,
        "SUCCEEDED",
        timeout=45,
    )

    print(
        "[VERIFY] task SUCCEEDED"
    )

    attempts = get_attempts(
        task_id
    )

    if len(attempts) != 2:

        raise AssertionError(
            "Expected exactly two "
            "physical attempts, got "
            f"{len(attempts)}"
        )

    attempts = sorted(
        attempts,
        key=lambda attempt:
            attempt["attemptNumber"],
    )

    first = attempts[0]
    second = attempts[1]

    if first["id"] != attempt1_id:

        raise AssertionError(
            "Attempt 1 identity changed"
        )

    if first["attemptNumber"] != 1:

        raise AssertionError(
            "First attempt number "
            "should be 1"
        )

    if first["status"] != "LOST":

        raise AssertionError(
            "Interrupted attempt should "
            "be LOST, got "
            f"{first['status']}"
        )

    if second["attemptNumber"] != 2:

        raise AssertionError(
            "Second attempt number "
            "should be 2"
        )

    if second["status"] != "SUCCEEDED":

        raise AssertionError(
            "Retry attempt should "
            "SUCCEED, got "
            f"{second['status']}"
        )

    if (
        "RECOVERY ATTEMPT DONE"
        not in (
            completed.get("stdout")
            or ""
        )
    ):

        raise AssertionError(
            "Successful retry output "
            "is missing"
        )

    events = get_events(
        task_id
    )

    ids = [
        event["id"]
        for event in events
    ]

    if ids != sorted(ids):

        raise AssertionError(
            "Timeline event IDs "
            "are not monotonic"
        )

    types = event_types(
        events
    )

    assert_subsequence(
        types,
        [
            "ATTEMPT_RUNNING",
            "TASK_RUNNING",

            "ATTEMPT_LOST",
            "TASK_LOST",

            "RETRY_SCHEDULED",

            "ATTEMPT_CREATED",
            "TASK_DISPATCHED",
            "ATTEMPT_DISPATCHED",
            "ATTEMPT_RUNNING",
            "TASK_RUNNING",

            "ATTEMPT_SUCCEEDED",
            "TASK_SUCCEEDED",
        ],
    )

    if types.count(
            "ATTEMPT_LOST") != 1:

        raise AssertionError(
            "Expected exactly one "
            "ATTEMPT_LOST event"
        )

    if types.count(
            "TASK_LOST") != 1:

        raise AssertionError(
            "Expected exactly one "
            "TASK_LOST event"
        )

    if types.count(
            "RETRY_SCHEDULED") != 1:

        raise AssertionError(
            "Expected exactly one "
            "RETRY_SCHEDULED event"
        )

    STATE_FILE.unlink(
        missing_ok=True
    )

    print()
    print(
        "RECOVERY TIMELINE PASS"
    )

    print(
        "Attempt 1 became LOST, "
        "Forge retried it as attempt 2, "
        "and the task succeeded."
    )


# ============================================================
# Main
# ============================================================

def main():

    if len(sys.argv) != 2:

        print(
            "Usage:"
        )

        print(
            "  python3 scripts/"
            "recovery_timeline_smoke_test.py "
            "prepare"
        )

        print(
            "  python3 scripts/"
            "recovery_timeline_smoke_test.py "
            "verify"
        )

        return 1

    mode = (
        sys.argv[1]
        .lower()
    )

    try:

        if mode == "prepare":

            prepare()

        elif mode == "verify":

            verify()

        else:

            raise ValueError(
                "Mode must be "
                "'prepare' or 'verify'"
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
