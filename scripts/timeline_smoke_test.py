#!/usr/bin/env python3

import json
import time
import urllib.error
import urllib.request
import uuid


BASE = "http://localhost:8080"
TASKS = f"{BASE}/api/tasks"
WORKFLOWS = f"{BASE}/api/workflows"
WORKERS = f"{BASE}/api/workers"

POLL_INTERVAL = 0.25
DEFAULT_TIMEOUT = 30


# ============================================================
# HTTP
# ============================================================

def request(method, url, body=None):
    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(
                req,
                timeout=10) as response:

            text = response.read().decode("utf-8")

            if not text:
                return None

            return json.loads(text)

    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8")

        raise RuntimeError(
            f"{method} {url} failed: "
            f"HTTP {exc.code}: {body}"
        )


def get(url):
    return request("GET", url)


def post(url, body=None):
    return request("POST", url, body)


# ============================================================
# Helpers
# ============================================================

def create_task(
        command,
        arguments,
        *,
        max_attempts=1,
        timeout_seconds=30,
        dependencies=None):

    body = {
        "command": command,
        "arguments": arguments,
        "maxAttempts": max_attempts,
        "timeoutSeconds": timeout_seconds,
    }

    if dependencies:
        body["dependsOnTaskIds"] = dependencies

    return post(
        TASKS,
        body,
    )


def task(task_id):
    return get(
        f"{TASKS}/{task_id}"
    )


def attempts(task_id):
    return get(
        f"{TASKS}/{task_id}/attempts"
    )


def task_events(task_id):
    return get(
        f"{TASKS}/{task_id}/events"
    )


def workflow_events(workflow_id):
    return get(
        f"{WORKFLOWS}/{workflow_id}/events"
    )


def wait_task(
        task_id,
        expected_status,
        timeout=DEFAULT_TIMEOUT):

    deadline = time.time() + timeout

    while time.time() < deadline:
        current = task(task_id)

        if current["status"] == expected_status:
            return current

        time.sleep(POLL_INTERVAL)

    current = task(task_id)

    raise AssertionError(
        f"Task {task_id}: expected "
        f"{expected_status}, got "
        f"{current['status']}"
    )


def wait_workflow(
        workflow_id,
        expected_status,
        timeout=DEFAULT_TIMEOUT):

    deadline = time.time() + timeout

    while time.time() < deadline:
        current = get(
            f"{WORKFLOWS}/{workflow_id}"
        )

        if current["status"] == expected_status:
            return current

        time.sleep(POLL_INTERVAL)

    current = get(
        f"{WORKFLOWS}/{workflow_id}"
    )

    raise AssertionError(
        f"Workflow {workflow_id}: expected "
        f"{expected_status}, got "
        f"{current['status']}"
    )


def types(events):
    return [
        event["type"]
        for event in events
    ]


def assert_subsequence(
        actual,
        expected,
        description):

    position = 0

    for item in actual:
        if position < len(expected) \
                and item == expected[position]:

            position += 1

    if position != len(expected):
        raise AssertionError(
            f"{description}\n"
            f"Expected subsequence:\n"
            f"  {expected}\n"
            f"Actual:\n"
            f"  {actual}"
        )


def assert_monotonic_ids(events):
    ids = [
        event["id"]
        for event in events
    ]

    if ids != sorted(ids):
        raise AssertionError(
            f"Event IDs are not ordered: {ids}"
        )

    if len(ids) != len(set(ids)):
        raise AssertionError(
            f"Duplicate event IDs: {ids}"
        )


def ensure_worker():
    workers = get(WORKERS)

    connected = [
        worker
        for worker in workers
        if worker["online"]
        and worker["commandStreamConnected"]
    ]

    if not connected:
        raise AssertionError(
            "No connected Forge worker"
        )

    print(
        "Worker:",
        connected[0]["id"],
    )


# ============================================================
# Tests
# ============================================================

def test_normal_lifecycle():
    print(
        "\n[TEST] normal task lifecycle"
    )

    created = create_task(
        "python3",
        [
            "-c",
            'print("timeline-normal")',
        ],
    )

    task_id = created["id"]

    wait_task(
        task_id,
        "SUCCEEDED",
    )

    events = task_events(task_id)
    event_types = types(events)

    assert_monotonic_ids(events)

    assert_subsequence(
        event_types,
        [
            "TASK_CREATED",
            "TASK_PENDING",
            "ATTEMPT_CREATED",
            "TASK_DISPATCHED",
            "ATTEMPT_DISPATCHED",
            "ATTEMPT_RUNNING",
            "TASK_RUNNING",
            "ATTEMPT_SUCCEEDED",
            "TASK_SUCCEEDED",
        ],
        "Normal lifecycle ordering is wrong",
    )

    print("  PASS")


def test_dependency_timeline():
    print(
        "\n[TEST] workflow dependency release"
    )

    workflow = post(
        WORKFLOWS,
        {
            "name": (
                "timeline-dependency-"
                + uuid.uuid4().hex[:8]
            ),
            "tasks": [
                {
                    "key": "a",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            "import time; "
                            "time.sleep(1); "
                            'print("A")'
                        ),
                    ],
                    "maxAttempts": 1,
                    "timeoutSeconds": 10,
                    "dependsOn": [],
                },
                {
                    "key": "b",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        'print("B")',
                    ],
                    "maxAttempts": 1,
                    "timeoutSeconds": 10,
                    "dependsOn": [
                        "a",
                    ],
                },
            ],
        },
    )

    workflow_id = workflow["id"]

    tasks_by_key = {
        item["key"]: item
        for item in workflow["tasks"]
    }

    a_id = tasks_by_key["a"]["taskId"]
    b_id = tasks_by_key["b"]["taskId"]

    wait_task(
        a_id,
        "SUCCEEDED",
    )

    wait_task(
        b_id,
        "SUCCEEDED",
    )

    workflow_event_types = types(
        workflow_events(workflow_id)
    )

    if "WORKFLOW_CREATED" \
            not in workflow_event_types:

        raise AssertionError(
            "WORKFLOW_CREATED missing"
        )

    b_events = task_events(b_id)

    assert_subsequence(
        types(b_events),
        [
            "TASK_CREATED",
            "TASK_BLOCKED",
            "TASK_PENDING",
            "ATTEMPT_CREATED",
            "TASK_DISPATCHED",
            "ATTEMPT_DISPATCHED",
            "ATTEMPT_RUNNING",
            "TASK_RUNNING",
            "ATTEMPT_SUCCEEDED",
            "TASK_SUCCEEDED",
        ],
        "Dependency release timeline is wrong",
    )

    print("  PASS")


def test_failure_and_skip():
    print(
        "\n[TEST] dependency failure -> skip"
    )

    parent = create_task(
        "python3",
        [
            "-c",
            (
                "import sys; "
                'print("parent failing"); '
                "sys.exit(1)"
            ),
        ],
        max_attempts=1,
    )

    child = create_task(
        "python3",
        [
            "-c",
            (
                'print("ERROR: '
                'skipped child executed")'
            ),
        ],
        dependencies=[
            parent["id"],
        ],
    )

    wait_task(
        parent["id"],
        "FAILED",
    )

    wait_task(
        child["id"],
        "SKIPPED",
    )

    if attempts(child["id"]) != []:
        raise AssertionError(
            "Skipped child unexpectedly executed"
        )

    assert_subsequence(
        types(
            task_events(
                child["id"]
            )
        ),
        [
            "TASK_CREATED",
            "TASK_BLOCKED",
            "TASK_SKIPPED",
        ],
        "Skipped task timeline is wrong",
    )

    print("  PASS")


def test_automatic_retry():
    print(
        "\n[TEST] automatic retry timeline"
    )

    marker = (
        "/tmp/forge-timeline-retry-"
        + uuid.uuid4().hex
    )

    code = (
        "from pathlib import Path; "
        "import sys; "
        f'p=Path("{marker}"); '
        "first=not p.exists(); "
        "p.touch(); "
        'print("first" if first else "second"); '
        "sys.exit(1 if first else 0)"
    )

    created = create_task(
        "python3",
        [
            "-c",
            code,
        ],
        max_attempts=2,
        timeout_seconds=10,
    )

    task_id = created["id"]

    completed = wait_task(
        task_id,
        "SUCCEEDED",
        timeout=25,
    )

    if "second" not in (
            completed.get("stdout") or ""):

        raise AssertionError(
            "Retry did not produce second-run output"
        )

    event_types = types(
        task_events(task_id)
    )

    if event_types.count(
            "RETRY_SCHEDULED") != 1:

        raise AssertionError(
            "Expected exactly one "
            "RETRY_SCHEDULED event, got "
            + str(
                event_types.count(
                    "RETRY_SCHEDULED"
                )
            )
        )

    assert_subsequence(
        event_types,
        [
            "ATTEMPT_FAILED",
            "TASK_FAILED",
            "RETRY_SCHEDULED",
            "ATTEMPT_CREATED",
            "ATTEMPT_DISPATCHED",
            "ATTEMPT_RUNNING",
            "ATTEMPT_SUCCEEDED",
            "TASK_SUCCEEDED",
        ],
        "Automatic retry timeline is wrong",
    )

    physical_attempts = attempts(
        task_id
    )

    if len(physical_attempts) != 2:
        raise AssertionError(
            "Expected 2 attempts, got "
            f"{len(physical_attempts)}"
        )

    statuses = [
        attempt["status"]
        for attempt in physical_attempts
    ]

    if statuses != [
            "FAILED",
            "SUCCEEDED"]:

        raise AssertionError(
            f"Unexpected retry statuses: "
            f"{statuses}"
        )

    print("  PASS")


def test_pre_dispatch_cancellation():
    print(
        "\n[TEST] blocked task cancellation"
    )

    parent = create_task(
        "python3",
        [
            "-c",
            (
                "import time; "
                "time.sleep(3); "
                'print("parent done")'
            ),
        ],
    )

    child = create_task(
        "python3",
        [
            "-c",
            'print("should not run")',
        ],
        dependencies=[
            parent["id"],
        ],
    )

    if task(child["id"])["status"] \
            != "BLOCKED":

        raise AssertionError(
            "Child was expected to be BLOCKED"
        )

    post(
        f"{TASKS}/{child['id']}/cancel"
    )

    wait_task(
        child["id"],
        "CANCELLED",
    )

    assert_subsequence(
        types(
            task_events(
                child["id"]
            )
        ),
        [
            "TASK_CREATED",
            "TASK_BLOCKED",
            "TASK_CANCELLED",
        ],
        "Cancellation timeline is wrong",
    )

    if attempts(child["id"]) != []:
        raise AssertionError(
            "Cancelled blocked task executed"
        )

    # Do not leave the parent running after
    # this test finishes.
    wait_task(
        parent["id"],
        "SUCCEEDED",
    )

    print("  PASS")


def test_workflow_retry():
    print(
        "\n[TEST] workflow retry timeline"
    )

    marker = (
        "/tmp/forge-workflow-retry-"
        + uuid.uuid4().hex
    )

    code = (
        "from pathlib import Path; "
        "import sys; "
        f'p=Path("{marker}"); '
        "first=not p.exists(); "
        "p.touch(); "
        'print("workflow-first" if first '
        'else "workflow-second"); '
        "sys.exit(1 if first else 0)"
    )

    workflow = post(
        WORKFLOWS,
        {
            "name": (
                "timeline-workflow-retry-"
                + uuid.uuid4().hex[:8]
            ),
            "tasks": [
                {
                    "key": "job",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        code,
                    ],
                    # No automatic retry.
                    "maxAttempts": 1,
                    "timeoutSeconds": 10,
                    "dependsOn": [],
                },
            ],
        },
    )

    workflow_id = workflow["id"]
    task_id = workflow["tasks"][0][
        "taskId"
    ]

    wait_workflow(
        workflow_id,
        "FAILED",
    )

    post(
        f"{WORKFLOWS}/{workflow_id}/retry"
    )

    wait_workflow(
        workflow_id,
        "SUCCEEDED",
    )

    workflow_type_list = types(
        workflow_events(
            workflow_id
        )
    )

    assert_subsequence(
        workflow_type_list,
        [
            "WORKFLOW_CREATED",
            "WORKFLOW_RETRY_REQUESTED",
        ],
        "Workflow retry event missing",
    )

    task_type_list = types(
        task_events(
            task_id
        )
    )

    assert_subsequence(
        task_type_list,
        [
            "TASK_FAILED",
            "TASK_PENDING",
            "ATTEMPT_CREATED",
            "TASK_SUCCEEDED",
        ],
        "Workflow task retry timeline is wrong",
    )

    print("  PASS")


def test_workflow_cancellation():
    print(
        "\n[TEST] workflow cancellation timeline"
    )

    workflow = post(
        WORKFLOWS,
        {
            "name": (
                "timeline-cancel-"
                + uuid.uuid4().hex[:8]
            ),
            "tasks": [
                {
                    "key": "root",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            "import time; "
                            "time.sleep(10)"
                        ),
                    ],
                    "maxAttempts": 1,
                    "timeoutSeconds": 20,
                    "dependsOn": [],
                },
                {
                    "key": "child",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        'print("never run")',
                    ],
                    "maxAttempts": 1,
                    "timeoutSeconds": 10,
                    "dependsOn": [
                        "root",
                    ],
                },
            ],
        },
    )

    workflow_id = workflow["id"]

    tasks_by_key = {
        item["key"]: item
        for item in workflow["tasks"]
    }

    root_id = tasks_by_key[
        "root"
    ]["taskId"]

    child_id = tasks_by_key[
        "child"
    ]["taskId"]

    wait_task(
        root_id,
        "RUNNING",
    )

    post(
        f"{WORKFLOWS}/{workflow_id}/cancel"
    )

    wait_workflow(
        workflow_id,
        "CANCELLED",
        timeout=15,
    )

    workflow_type_list = types(
        workflow_events(
            workflow_id
        )
    )

    if workflow_type_list.count(
            "WORKFLOW_CANCEL_REQUESTED") != 1:

        raise AssertionError(
            "Expected exactly one "
            "WORKFLOW_CANCEL_REQUESTED event"
        )

    if "TASK_CANCELLED" not in types(
            task_events(child_id)):

        raise AssertionError(
            "Blocked workflow child is "
            "missing TASK_CANCELLED"
        )

    print("  PASS")


# ============================================================
# Main
# ============================================================

def main():
    print(
        "Forge execution timeline smoke tests"
    )
    print(
        "===================================="
    )

    ensure_worker()

    tests = [
        test_normal_lifecycle,
        test_dependency_timeline,
        test_failure_and_skip,
        test_automatic_retry,
        test_pre_dispatch_cancellation,
        test_workflow_retry,
        test_workflow_cancellation,
    ]

    passed = 0

    for test in tests:
        test()
        passed += 1

    print()
    print(
        "===================================="
    )
    print(
        f"ALL {passed}/{len(tests)} "
        "TIMELINE TESTS PASSED"
    )


if __name__ == "__main__":
    main()
