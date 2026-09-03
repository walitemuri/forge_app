#!/usr/bin/env python3

import json
import sys
import time
import urllib.error
import urllib.request


BASE_URL = "http://localhost:8080/api/tasks"
POLL_INTERVAL = 0.25
DEFAULT_TIMEOUT = 30

WORKFLOW_URL = "http://localhost:8080/api/workflows"

# ============================================================
# HTTP helpers
# ============================================================

def request_json(method, url, body=None):
    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )

    try:
        with urllib.request.urlopen(req) as response:
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

def create_workflow(payload):
    return request_json(
        "POST",
        WORKFLOW_URL,
        payload,
    )

def create_task(
        command,
        arguments,
        *,
        max_attempts=1,
        timeout_seconds=30,
        dependencies=None):

    payload = {
        "command": command,
        "arguments": arguments,
        "maxAttempts": max_attempts,
        "timeoutSeconds": timeout_seconds,
    }

    if dependencies:
        payload["dependsOnTaskIds"] = dependencies

    task = request_json(
        "POST",
        BASE_URL,
        payload,
    )

    print(
        f"  created {task['id']} "
        f"status={task['status']}"
    )

    return task


def get_task(task_id):
    return request_json(
        "GET",
        f"{BASE_URL}/{task_id}",
    )


def get_attempts(task_id):
    return request_json(
        "GET",
        f"{BASE_URL}/{task_id}/attempts",
    )


def wait_for_status(
        task_id,
        expected_status,
        timeout=DEFAULT_TIMEOUT):

    deadline = time.time() + timeout

    while time.time() < deadline:
        task = get_task(task_id)

        if task["status"] == expected_status:
            return task

        time.sleep(POLL_INTERVAL)

    task = get_task(task_id)

    raise AssertionError(
        f"Task {task_id}: expected "
        f"{expected_status}, got {task['status']}"
    )


def assert_status(task_id, expected):
    actual = get_task(task_id)["status"]

    if actual != expected:
        raise AssertionError(
            f"Task {task_id}: "
            f"expected {expected}, got {actual}"
        )


def assert_no_attempts(task_id):
    attempts = get_attempts(task_id)

    if attempts != []:
        raise AssertionError(
            f"Task {task_id} should have no attempts, "
            f"got {len(attempts)}"
        )


# ============================================================
# Tests
# ============================================================

def test_fan_in():
    print("\n[TEST] fan-in: A + B -> C")

    parent_a = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANIN A START", flush=True); '
                'time.sleep(2); '
                'print("FANIN A DONE", flush=True)'
            ),
        ],
    )

    parent_b = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANIN B START", flush=True); '
                'time.sleep(5); '
                'print("FANIN B DONE", flush=True)'
            ),
        ],
    )

    child = create_task(
        "python3",
        [
            "-c",
            'print("FANIN CHILD EXECUTED")',
        ],
        dependencies=[
            parent_a["id"],
            parent_b["id"],
        ],
    )

    assert_status(
        child["id"],
        "BLOCKED",
    )

    assert_no_attempts(
        child["id"],
    )

    wait_for_status(
        parent_a["id"],
        "SUCCEEDED",
    )

    # A is done but B should still be running.
    assert_status(
        child["id"],
        "BLOCKED",
    )

    assert_no_attempts(
        child["id"],
    )

    wait_for_status(
        parent_b["id"],
        "SUCCEEDED",
    )

    wait_for_status(
        child["id"],
        "SUCCEEDED",
    )

    print("  PASS")

def test_workflow_submission():
    print(
        "\n[TEST] workflow submission API"
    )

    workflow = create_workflow(
        {
            "name": "smoke-build",
            "tasks": [
                {
                    "key": "checkout",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("CHECKOUT", flush=True); '
                            'time.sleep(1)'
                        ),
                    ],
                },
                {
                    "key": "compile",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("COMPILE", flush=True); '
                            'time.sleep(1)'
                        ),
                    ],
                    "dependsOn": [
                        "checkout",
                    ],
                },
                {
                    "key": "test",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        'print("TEST", flush=True)',
                    ],
                    "dependsOn": [
                        "compile",
                    ],
                },
                {
                    "key": "package",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        'print("PACKAGE", flush=True)',
                    ],
                    "dependsOn": [
                        "compile",
                        "test",
                    ],
                },
            ],
        }
    )
def test_workflow_cycle_rejected():
    print(
        "\n[TEST] workflow cycle rejection"
    )

    payload = {
        "name": "invalid-cycle",
        "tasks": [
            {
                "key": "a",
                "command": "python3",
                "arguments": [
                    "-c",
                    'print("A")',
                ],
                "dependsOn": [
                    "b",
                ],
            },
            {
                "key": "b",
                "command": "python3",
                "arguments": [
                    "-c",
                    'print("B")',
                ],
                "dependsOn": [
                    "a",
                ],
            },
        ],
    }

    try:
        create_workflow(
            payload
        )

    except RuntimeError as exc:

        if "400" not in str(exc):
            raise

        if "cycle" not in str(exc).lower():
            raise AssertionError(
                "Cycle rejection did not mention cycle"
            )

        print("  PASS")
        return

    raise AssertionError(
        "Cyclic workflow was accepted"
    )

    if workflow["name"] != "smoke-build":
        raise AssertionError(
            "Workflow name mismatch"
        )

    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }

    expected_keys = {
        "checkout",
        "compile",
        "test",
        "package",
    }

    if set(tasks.keys()) != expected_keys:
        raise AssertionError(
            "Workflow task keys mismatch"
        )

    if tasks["checkout"]["status"] != "PENDING":
        raise AssertionError(
            "checkout should initially be PENDING"
        )

    for key in [
        "compile",
        "test",
        "package",
    ]:
        if tasks[key]["status"] != "BLOCKED":
            raise AssertionError(
                f"{key} should initially be BLOCKED"
            )

    for key in [
        "checkout",
        "compile",
        "test",
        "package",
    ]:
        wait_for_status(
            tasks[key]["taskId"],
            "SUCCEEDED",
        )

    print("  PASS")
def test_fan_out():
    print("\n[TEST] fan-out: A -> B + C")

    parent = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANOUT PARENT START", flush=True); '
                'time.sleep(3); '
                'print("FANOUT PARENT DONE", flush=True)'
            ),
        ],
    )

    child_b = create_task(
        "python3",
        [
            "-c",
            'print("FANOUT CHILD B")',
        ],
        dependencies=[
            parent["id"],
        ],
    )

    child_c = create_task(
        "python3",
        [
            "-c",
            'print("FANOUT CHILD C")',
        ],
        dependencies=[
            parent["id"],
        ],
    )

    assert_status(
        child_b["id"],
        "BLOCKED",
    )

    assert_status(
        child_c["id"],
        "BLOCKED",
    )

    assert_no_attempts(
        child_b["id"],
    )

    assert_no_attempts(
        child_c["id"],
    )

    wait_for_status(
        parent["id"],
        "SUCCEEDED",
    )

    wait_for_status(
        child_b["id"],
        "SUCCEEDED",
    )

    wait_for_status(
        child_c["id"],
        "SUCCEEDED",
    )

    print("  PASS")


def test_multi_parent_failure():
    print(
        "\n[TEST] multi-parent failure: "
        "A succeeds + B fails -> C skipped"
    )

    parent_a = create_task(
        "python3",
        [
            "-c",
            'print("FAILTEST A SUCCESS", flush=True)',
        ],
    )

    parent_b = create_task(
        "python3",
        [
            "-c",
            (
                'import sys; '
                'print("FAILTEST B FAIL", flush=True); '
                'sys.exit(1)'
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
                'SKIPPED CHILD EXECUTED")'
            ),
        ],
        dependencies=[
            parent_a["id"],
            parent_b["id"],
        ],
    )

    wait_for_status(
        parent_a["id"],
        "SUCCEEDED",
    )

    wait_for_status(
        parent_b["id"],
        "FAILED",
    )

    wait_for_status(
        child["id"],
        "SKIPPED",
    )

    assert_no_attempts(
        child["id"],
    )

    print("  PASS")


def test_transitive_skip():
    print(
        "\n[TEST] transitive skip: "
        "A fails -> B skipped -> C skipped"
    )

    parent_a = create_task(
        "python3",
        [
            "-c",
            (
                'import sys; '
                'print("CHAIN A FAIL", flush=True); '
                'sys.exit(1)'
            ),
        ],
        max_attempts=1,
    )

    task_b = create_task(
        "python3",
        [
            "-c",
            'print("ERROR: CHAIN B EXECUTED")',
        ],
        dependencies=[
            parent_a["id"],
        ],
    )

    task_c = create_task(
        "python3",
        [
            "-c",
            'print("ERROR: CHAIN C EXECUTED")',
        ],
        dependencies=[
            task_b["id"],
        ],
    )

    wait_for_status(
        parent_a["id"],
        "FAILED",
    )

    wait_for_status(
        task_b["id"],
        "SKIPPED",
    )

    wait_for_status(
        task_c["id"],
        "SKIPPED",
    )

    assert_no_attempts(
        task_b["id"],
    )

    assert_no_attempts(
        task_c["id"],
    )

    print("  PASS")

def test_argument_order():
    print("\n[TEST] persisted argument ordering")

    task = create_task(
        "python3",
        [
            "-c",
            (
                'import sys; '
                'print("|".join(sys.argv[1:]), flush=True)'
            ),
            "first",
            "second",
            "third",
            "fourth",
        ],
    )

    completed = wait_for_status(
        task["id"],
        "SUCCEEDED",
    )

    expected = "first|second|third|fourth"
    actual = completed["stdout"].strip()

    if actual != expected:
        raise AssertionError(
            f"Expected argv '{expected}', "
            f"got '{actual}'"
        )

    print("  PASS")

# ============================================================
# Main
# ============================================================

def main():
    print("Forge DAG smoke tests")
    print("=====================")

    # Fail immediately if controller isn't running.
    try:
        urllib.request.urlopen(
            BASE_URL + "/does-not-exist",
            timeout=2,
        )
    except urllib.error.HTTPError:
        # 404 is fine — controller is reachable.
        pass
    except Exception as exc:
        print(
            f"\nERROR: Controller is not reachable: {exc}"
        )
        return 1

    tests = [
        test_argument_order,
        test_fan_in,
        test_fan_out,
        test_multi_parent_failure,
        test_transitive_skip,
        test_workflow_submission,
        test_workflow_cycle_rejected,
    ]

    passed = 0

    for test in tests:
        try:
            test()
            passed += 1

        except Exception as exc:
            print(f"  FAIL: {exc}")

            print(
                f"\n{passed}/{len(tests)} tests passed."
            )

            return 1

    print("\n=====================")
    print(
        f"ALL {passed}/{len(tests)} TESTS PASSED"
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
