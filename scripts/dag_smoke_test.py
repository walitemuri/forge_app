#!/usr/bin/env python3

import json
import sys
import time
import urllib.error
import urllib.request
import time


BASE_URL = "http://localhost:8080/api/tasks"
WORKFLOW_URL = "http://localhost:8080/api/workflows"
WORKER_URL = (
    "http://localhost:8080/api/workers"
)
POLL_INTERVAL = 0.25
DEFAULT_TIMEOUT = 30


# ============================================================
# HTTP helpers
# ============================================================
def get_workers():
    return request_json(
        "GET",
        WORKER_URL,
    )
    
def request_json(method, url, body=None):
    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")

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

            text = response.read().decode(
                "utf-8"
            )

            if not text:
                return None

            return json.loads(
                text
            )

    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode(
            "utf-8"
        )

        raise RuntimeError(
            f"{method} {url} failed: "
            f"HTTP {exc.code}: "
            f"{error_body}"
        )


# ============================================================
# Task helpers
# ============================================================
def wait_for_worker_condition(
        worker_id,
        predicate,
        description,
        timeout=15):

    deadline = (
        time.time()
        + timeout
    )


    while time.time() < deadline:

        workers = get_workers()


        worker = next(
            (
                worker
                for worker in workers
                if worker["id"] == worker_id
            ),
            None,
        )


        if worker is not None \
                and predicate(worker):

            return worker


        time.sleep(0.25)


    raise AssertionError(
        "Timed out waiting for worker "
        f"{worker_id}: {description}"
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
        payload["dependsOnTaskIds"] = (
            dependencies
        )

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
        task = get_task(
            task_id
        )

        if task["status"] == expected_status:
            return task

        time.sleep(
            POLL_INTERVAL
        )

    task = get_task(
        task_id
    )

    raise AssertionError(
        f"Task {task_id}: expected "
        f"{expected_status}, got "
        f"{task['status']}"
    )


def assert_status(
        task_id,
        expected):

    actual = get_task(
        task_id
    )["status"]

    if actual != expected:
        raise AssertionError(
            f"Task {task_id}: "
            f"expected {expected}, "
            f"got {actual}"
        )


def assert_no_attempts(
        task_id):

    attempts = get_attempts(
        task_id
    )

    if attempts != []:
        raise AssertionError(
            f"Task {task_id} should "
            f"have no attempts, got "
            f"{len(attempts)}"
        )


# ============================================================
# Workflow helpers
# ============================================================

def create_workflow(payload):
    return request_json(
        "POST",
        WORKFLOW_URL,
        payload,
    )


def get_workflow(workflow_id):
    return request_json(
        "GET",
        f"{WORKFLOW_URL}/{workflow_id}",
    )

def retry_workflow(workflow_id):
    return request_json(
        "POST",
        f"{WORKFLOW_URL}/{workflow_id}/retry",
    )

# ============================================================
# Tests
# ============================================================

def test_argument_order():
    print(
        "\n[TEST] persisted argument ordering"
    )

    task = create_task(
        "python3",
        [
            "-c",
            (
                'import sys; '
                'print("|".join(sys.argv[1:]), '
                'flush=True)'
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

    expected = (
        "first|second|third|fourth"
    )

    actual = completed[
        "stdout"
    ].strip()

    if actual != expected:
        raise AssertionError(
            f"Expected argv '{expected}', "
            f"got '{actual}'"
        )

    print("  PASS")


def test_fan_in():
    print(
        "\n[TEST] fan-in: A + B -> C"
    )

    parent_a = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANIN A START", '
                'flush=True); '
                'time.sleep(2); '
                'print("FANIN A DONE", '
                'flush=True)'
            ),
        ],
    )

    parent_b = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANIN B START", '
                'flush=True); '
                'time.sleep(5); '
                'print("FANIN B DONE", '
                'flush=True)'
            ),
        ],
    )

    child = create_task(
        "python3",
        [
            "-c",
            (
                'print('
                '"FANIN CHILD EXECUTED"'
                ')'
            ),
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

    # A succeeded, but B has not.
    # C must still be blocked.
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


def test_fan_out():
    print(
        "\n[TEST] fan-out: A -> B + C"
    )

    parent = create_task(
        "python3",
        [
            "-c",
            (
                'import time; '
                'print("FANOUT PARENT START", '
                'flush=True); '
                'time.sleep(3); '
                'print("FANOUT PARENT DONE", '
                'flush=True)'
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
            (
                'print('
                '"FAILTEST A SUCCESS", '
                'flush=True)'
            ),
        ],
    )

    parent_b = create_task(
        "python3",
        [
            "-c",
            (
                'import sys; '
                'print("FAILTEST B FAIL", '
                'flush=True); '
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
                'print('
                '"ERROR: SKIPPED CHILD EXECUTED"'
                ')'
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
                'print("CHAIN A FAIL", '
                'flush=True); '
                'sys.exit(1)'
            ),
        ],
        max_attempts=1,
    )

    task_b = create_task(
        "python3",
        [
            "-c",
            (
                'print('
                '"ERROR: CHAIN B EXECUTED"'
                ')'
            ),
        ],
        dependencies=[
            parent_a["id"],
        ],
    )

    task_c = create_task(
        "python3",
        [
            "-c",
            (
                'print('
                '"ERROR: CHAIN C EXECUTED"'
                ')'
            ),
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


def test_workflow_submission():
    print(
        "\n[TEST] workflow submission + retrieval"
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
                            'print("CHECKOUT", '
                            'flush=True); '
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
                            'print("COMPILE", '
                            'flush=True); '
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
                        (
                            'print("TEST", '
                            'flush=True)'
                        ),
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
                        (
                            'print("PACKAGE", '
                            'flush=True)'
                        ),
                    ],
                    "dependsOn": [
                        "compile",
                        "test",
                    ],
                },
            ],
        }
    )

    workflow_id = workflow["id"]

    if workflow["name"] != "smoke-build":
        raise AssertionError(
            "Workflow name mismatch"
        )

    if workflow["status"] != "PENDING":
        raise AssertionError(
            "Workflow should initially "
            f"be PENDING, got "
            f"{workflow['status']}"
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
            "checkout should initially "
            "be PENDING"
        )

    for key in [
        "compile",
        "test",
        "package",
    ]:

        if tasks[key]["status"] != "BLOCKED":
            raise AssertionError(
                f"{key} should initially "
                "be BLOCKED"
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

    persisted = get_workflow(
        workflow_id
    )

    if persisted["id"] != workflow_id:
        raise AssertionError(
            "workflow id changed "
            "after retrieval"
        )

    if persisted["name"] != "smoke-build":
        raise AssertionError(
            "workflow name mismatch "
            "after retrieval"
        )

    if persisted["status"] != "SUCCEEDED":
        raise AssertionError(
            "Workflow should be SUCCEEDED, "
            f"got {persisted['status']}"
        )

    persisted_tasks = {
        task["key"]: task
        for task in persisted["tasks"]
    }

    expected_dependencies = {
        "checkout": [],
        "compile": [
            "checkout",
        ],
        "test": [
            "compile",
        ],
        "package": [
            "compile",
            "test",
        ],
    }

    for key, dependencies in (
            expected_dependencies.items()):

        task = persisted_tasks[
            key
        ]

        if task["status"] != "SUCCEEDED":
            raise AssertionError(
                f"{key} should be "
                f"SUCCEEDED, got "
                f"{task['status']}"
            )

        if set(task["dependsOn"]) != \
                set(dependencies):

            raise AssertionError(
                f"{key} dependencies "
                f"incorrect: "
                f"{task['dependsOn']}"
            )

    print("  PASS")


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
        message = str(
            exc
        )

        if "400" not in message:
            raise

        if "cycle" not in message.lower():
            raise AssertionError(
                "Cycle rejection did "
                "not mention cycle"
            )

        print("  PASS")
        return

    raise AssertionError(
        "Cyclic workflow was accepted"
    )


def test_workflow_failure_status():
    print(
        "\n[TEST] workflow aggregate "
        "failure status"
    )

    workflow = create_workflow(
        {
            "name": "failure-workflow",
            "tasks": [
                {
                    "key": "parent",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import sys; '
                            'print("PARENT FAIL", '
                            'flush=True); '
                            'sys.exit(1)'
                        ),
                    ],
                    "maxAttempts": 1,
                },
                {
                    "key": "child",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print('
                            '"ERROR: CHILD EXECUTED"'
                            ')'
                        ),
                    ],
                    "dependsOn": [
                        "parent",
                    ],
                },
            ],
        }
    )

    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }

    wait_for_status(
        tasks["parent"]["taskId"],
        "FAILED",
    )

    wait_for_status(
        tasks["child"]["taskId"],
        "SKIPPED",
    )

    persisted = get_workflow(
        workflow["id"]
    )

    if persisted["status"] != "FAILED":
        raise AssertionError(
            "Workflow should be FAILED, "
            f"got {persisted['status']}"
        )

    assert_no_attempts(
        tasks["child"]["taskId"]
    )

    print("  PASS")

def cancel_workflow(workflow_id):
    return request_json(
        "POST",
        f"{WORKFLOW_URL}/{workflow_id}/cancel",
    )

def test_workflow_cancellation():
    print(
        "\n[TEST] workflow cancellation"
    )

    workflow = create_workflow(
        {
            "name": "cancel-workflow",
            "tasks": [
                {
                    "key": "long-running",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("LONG START", flush=True); '
                            'time.sleep(20); '
                            'print("ERROR: LONG FINISHED", '
                            'flush=True)'
                        ),
                    ],
                    "timeoutSeconds": 30,
                },
                {
                    "key": "blocked-child",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print("ERROR: CHILD EXECUTED", '
                            'flush=True)'
                        ),
                    ],
                    "dependsOn": [
                        "long-running",
                    ],
                },
            ],
        }
    )


    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }


    wait_for_status(
        tasks["long-running"]["taskId"],
        "RUNNING",
    )


    assert_status(
        tasks["blocked-child"]["taskId"],
        "BLOCKED",
    )


    cancel_workflow(
        workflow["id"]
    )


    wait_for_status(
        tasks["long-running"]["taskId"],
        "CANCELLED",
    )


    wait_for_status(
        tasks["blocked-child"]["taskId"],
        "CANCELLED",
    )


    assert_no_attempts(
        tasks["blocked-child"]["taskId"]
    )


    persisted = get_workflow(
        workflow["id"]
    )


    if persisted["status"] != "CANCELLED":
        raise AssertionError(
            "Workflow should be CANCELLED, "
            f"got {persisted['status']}"
        )


    print("  PASS")

def get_workflows():
    return request_json(
        "GET",
        WORKFLOW_URL,
    )

def test_workflow_listing():
    print(
        "\n[TEST] workflow history listing"
    )

    workflow = create_workflow(
        {
            "name": "history-test",
            "tasks": [
                {
                    "key": "hello",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print("HELLO", '
                            'flush=True)'
                        ),
                    ],
                },
            ],
        }
    )


    wait_for_status(
        workflow["tasks"][0]["taskId"],
        "SUCCEEDED",
    )


    workflows = get_workflows()


    matching = [
        item
        for item in workflows
        if item["id"] == workflow["id"]
    ]


    if len(matching) != 1:
        raise AssertionError(
            "Created workflow was not "
            "found in workflow history"
        )


    summary = matching[0]


    if summary["name"] != "history-test":
        raise AssertionError(
            "Workflow history name mismatch"
        )


    if summary["status"] != "SUCCEEDED":
        raise AssertionError(
            "Workflow history should show "
            f"SUCCEEDED, got {summary['status']}"
        )


    if summary["taskCount"] != 1:
        raise AssertionError(
            "Workflow history should report "
            "one task"
        )


    print("  PASS")

def test_workflow_retry():
    print(
        "\n[TEST] workflow retry after cancellation"
    )


    workflow = create_workflow(
        {
            "name": "retry-workflow",
            "tasks": [
                {
                    "key": "root",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("ROOT START", '
                            'flush=True); '
                            'time.sleep(3); '
                            'print("ROOT DONE", '
                            'flush=True)'
                        ),
                    ],
                    "timeoutSeconds": 30,
                },
                {
                    "key": "child",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print("CHILD DONE", '
                            'flush=True)'
                        ),
                    ],
                    "dependsOn": [
                        "root",
                    ],
                },
            ],
        }
    )


    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }


    root_id = (
        tasks["root"]["taskId"]
    )

    child_id = (
        tasks["child"]["taskId"]
    )


    wait_for_status(
        root_id,
        "RUNNING",
    )


    assert_status(
        child_id,
        "BLOCKED",
    )


    cancel_workflow(
        workflow["id"]
    )


    wait_for_status(
        root_id,
        "CANCELLED",
    )


    wait_for_status(
        child_id,
        "CANCELLED",
    )


    retry_response = retry_workflow(
        workflow["id"]
    )


    retry_tasks = {
        task["key"]: task
        for task in retry_response["tasks"]
    }


    if retry_tasks["root"]["status"] != "PENDING":
        raise AssertionError(
            "Retry root should be PENDING, "
            f"got {retry_tasks['root']['status']}"
        )


    if retry_tasks["child"]["status"] != "BLOCKED":
        raise AssertionError(
            "Retry child should be BLOCKED, "
            f"got {retry_tasks['child']['status']}"
        )


    wait_for_status(
        root_id,
        "SUCCEEDED",
    )


    wait_for_status(
        child_id,
        "SUCCEEDED",
    )


    root_attempts = get_attempts(
        root_id
    )


    if len(root_attempts) != 2:
        raise AssertionError(
            "Root should have exactly "
            f"2 attempts, got "
            f"{len(root_attempts)}"
        )


    attempt_numbers = [
        attempt["attemptNumber"]
        for attempt in root_attempts
    ]


    if attempt_numbers != [1, 2]:
        raise AssertionError(
            "Expected root attempts "
            f"[1, 2], got "
            f"{attempt_numbers}"
        )


    child_attempts = get_attempts(
        child_id
    )


    if len(child_attempts) != 1:
        raise AssertionError(
            "Child should execute exactly once, "
            f"got {len(child_attempts)} attempts"
        )


    persisted = get_workflow(
        workflow["id"]
    )


    if persisted["status"] != "SUCCEEDED":
        raise AssertionError(
            "Retried workflow should "
            "be SUCCEEDED, got "
            f"{persisted['status']}"
        )


    print("  PASS")
    
def test_workflow_selective_retry():
    print(
        "\n[TEST] workflow selective retry "
        "preserves successful tasks"
    )


    workflow = create_workflow(
        {
            "name": "selective-retry-workflow",
            "tasks": [
                {
                    "key": "prepare",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("PREPARE", flush=True); '
                            'time.sleep(1)'
                        ),
                    ],
                },
                {
                    "key": "build",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import time; '
                            'print("BUILD START", flush=True); '
                            'time.sleep(20); '
                            'print("BUILD DONE", flush=True)'
                        ),
                    ],
                    "timeoutSeconds": 30,
                    "dependsOn": [
                        "prepare",
                    ],
                },
                {
                    "key": "package",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print("PACKAGE", '
                            'flush=True)'
                        ),
                    ],
                    "dependsOn": [
                        "build",
                    ],
                },
            ],
        }
    )


    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }


    prepare_id = (
        tasks["prepare"]["taskId"]
    )

    build_id = (
        tasks["build"]["taskId"]
    )

    package_id = (
        tasks["package"]["taskId"]
    )


    # --------------------------------------------------------
    # Let the successful upstream task finish.
    # --------------------------------------------------------

    wait_for_status(
        prepare_id,
        "SUCCEEDED",
    )


    wait_for_status(
        build_id,
        "RUNNING",
    )


    assert_status(
        package_id,
        "BLOCKED",
    )


    prepare_attempts_before = (
        get_attempts(
            prepare_id
        )
    )


    if len(prepare_attempts_before) != 1:
        raise AssertionError(
            "prepare should have exactly "
            "one attempt before retry"
        )


    # --------------------------------------------------------
    # Cancel while the middle node is running.
    #
    # prepare:
    #     already SUCCEEDED
    #
    # build:
    #     RUNNING -> CANCELLED
    #
    # package:
    #     BLOCKED -> CANCELLED
    # --------------------------------------------------------

    cancel_workflow(
        workflow["id"]
    )


    wait_for_status(
        build_id,
        "CANCELLED",
    )


    wait_for_status(
        package_id,
        "CANCELLED",
    )


    assert_status(
        prepare_id,
        "SUCCEEDED",
    )


    # --------------------------------------------------------
    # Retry the workflow.
    #
    # prepare must remain untouched.
    #
    # build + package should be reopened.
    # --------------------------------------------------------

    retry_response = retry_workflow(
        workflow["id"]
    )


    retry_tasks = {
        task["key"]: task
        for task in retry_response["tasks"]
    }


    if retry_tasks["prepare"]["status"] != \
            "SUCCEEDED":

        raise AssertionError(
            "Successful prepare task "
            "should remain SUCCEEDED"
        )


    if retry_tasks["build"]["status"] != \
            "BLOCKED":

        raise AssertionError(
            "build should initially be "
            "BLOCKED after retry because "
            "it has a dependency"
        )


    if retry_tasks["package"]["status"] != \
            "BLOCKED":

        raise AssertionError(
            "package should initially "
            "be BLOCKED after retry"
        )


    # --------------------------------------------------------
    # DependencyCoordinator should notice that prepare
    # already succeeded and release build.
    # --------------------------------------------------------

    wait_for_status(
        build_id,
        "SUCCEEDED",
    )


    wait_for_status(
        package_id,
        "SUCCEEDED",
    )


    # --------------------------------------------------------
    # The successful upstream task must NOT run again.
    # --------------------------------------------------------

    prepare_attempts_after = (
        get_attempts(
            prepare_id
        )
    )


    if len(prepare_attempts_after) != 1:
        raise AssertionError(
            "prepare was incorrectly rerun; "
            f"expected 1 attempt, got "
            f"{len(prepare_attempts_after)}"
        )


    # --------------------------------------------------------
    # build ran once before cancellation and once after retry.
    # --------------------------------------------------------

    build_attempts = (
        get_attempts(
            build_id
        )
    )


    if len(build_attempts) != 2:
        raise AssertionError(
            "build should have exactly "
            f"2 attempts, got "
            f"{len(build_attempts)}"
        )


    build_attempt_numbers = [
        attempt["attemptNumber"]
        for attempt in build_attempts
    ]


    if build_attempt_numbers != [1, 2]:
        raise AssertionError(
            "Expected build attempt numbers "
            f"[1, 2], got "
            f"{build_attempt_numbers}"
        )


    # --------------------------------------------------------
    # package was cancelled while BLOCKED.
    #
    # Therefore it never had an original physical attempt.
    # It should execute exactly once after retry.
    # --------------------------------------------------------

    package_attempts = (
        get_attempts(
            package_id
        )
    )


    if len(package_attempts) != 1:
        raise AssertionError(
            "package should execute exactly "
            f"once, got "
            f"{len(package_attempts)} attempts"
        )


    persisted = get_workflow(
        workflow["id"]
    )


    if persisted["status"] != "SUCCEEDED":
        raise AssertionError(
            "Selective retry workflow should "
            "finish SUCCEEDED, got "
            f"{persisted['status']}"
        )


    print("  PASS")

def test_worker_listing():
    print(
        "\n[TEST] worker monitoring API"
    )


    workers = get_workers()


    if not workers:
        raise AssertionError(
            "Expected at least one "
            "connected Forge worker"
        )


    online_workers = [
        worker
        for worker in workers
        if worker["online"]
    ]


    if not online_workers:
        raise AssertionError(
            "Expected at least one "
            "online Forge worker"
        )


    connected_workers = [
        worker
        for worker in online_workers
        if worker[
            "commandStreamConnected"
        ]
    ]


    if not connected_workers:
        raise AssertionError(
            "Expected at least one worker "
            "with a command stream"
        )


    for worker in connected_workers:

        if worker["cpuCores"] < 1:
            raise AssertionError(
                "Worker reported invalid "
                "CPU core count"
            )


        if worker["memoryBytes"] <= 0:
            raise AssertionError(
                "Worker reported invalid "
                "memory size"
            )


        if worker["capacity"] < 1:
            raise AssertionError(
                "Worker reported invalid "
                "task capacity"
            )


        if worker["runningTasks"] < 0:
            raise AssertionError(
                "Worker reported negative "
                "running task count"
            )


        if worker["outstandingTasks"] < 0:
            raise AssertionError(
                "Worker reported negative "
                "outstanding task count"
            )


        if worker["lastHeartbeat"] <= 0:
            raise AssertionError(
                "Worker has invalid "
                "heartbeat timestamp"
            )


    print(
        "  workers:",
        len(workers),
    )

    print(
        "  online:",
        len(online_workers),
    )

    print("  PASS")
    
def test_worker_live_load():
    print(
        "\n[TEST] worker live load monitoring"
    )


    task = create_task(
        command="python3",
        arguments=[
            "-c",
            (
                'import time; '
                'print("LOAD TEST START", '
                'flush=True); '
                'time.sleep(8); '
                'print("LOAD TEST DONE", '
                'flush=True)'
            ),
        ],
        timeout_seconds=20,
    )


    task_id = (
        task["id"]
    )


    wait_for_status(
        task_id,
        "RUNNING",
    )


    running_task = get_task(
        task_id
    )


    worker_id = (
        running_task["workerId"]
    )


    if not worker_id:
        raise AssertionError(
            "RUNNING task should have "
            "an assigned worker"
        )


    # Controller-side reservation should already
    # reflect the task.
    busy_worker = (
        wait_for_worker_condition(
            worker_id,
            lambda worker:
                worker[
                    "outstandingTasks"
                ] >= 1,
            "outstandingTasks >= 1",
            timeout=5,
        )
    )


    if not busy_worker["online"]:
        raise AssertionError(
            "Executing worker should "
            "still be online"
        )


    # runningTasks comes from worker heartbeats,
    # which currently arrive every 5 seconds.
    heartbeat_busy_worker = (
        wait_for_worker_condition(
            worker_id,
            lambda worker:
                worker[
                    "runningTasks"
                ] >= 1,
            "runningTasks >= 1",
            timeout=10,
        )
    )


    print(
        "  busy worker:",
        worker_id,
    )

    print(
        "  running:",
        heartbeat_busy_worker[
            "runningTasks"
        ],
    )

    print(
        "  outstanding:",
        heartbeat_busy_worker[
            "outstandingTasks"
        ],
    )


    wait_for_status(
        task_id,
        "SUCCEEDED",
    )


    # The controller releases its reservation
    # immediately when TaskResult arrives.
    wait_for_worker_condition(
        worker_id,
        lambda worker:
            worker[
                "outstandingTasks"
            ] == 0,
        "outstandingTasks == 0",
        timeout=5,
    )


    # Worker heartbeat may still contain the previous
    # running count for a few seconds.
    idle_worker = (
        wait_for_worker_condition(
            worker_id,
            lambda worker:
                worker[
                    "runningTasks"
                ] == 0,
            "runningTasks == 0",
            timeout=10,
        )
    )


    if idle_worker[
            "outstandingTasks"
    ] != 0:

        raise AssertionError(
            "Worker reservation should "
            "be released after completion"
        )


    print("  PASS")
def test_workflow_cancel_suppresses_retry():
    print(
        "\n[TEST] workflow cancellation "
        "suppresses automatic retry"
    )


    workflow = create_workflow(
        {
            "name": "cancel-retry-guard",
            "tasks": [
                {
                    "key": "failing-root",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'import sys; '
                            'print("FAIL", flush=True); '
                            'sys.exit(1)'
                        ),
                    ],
                    "maxAttempts": 3,
                },
                {
                    "key": "child",
                    "command": "python3",
                    "arguments": [
                        "-c",
                        (
                            'print('
                            '"ERROR: CHILD EXECUTED", '
                            'flush=True)'
                        ),
                    ],
                    "dependsOn": [
                        "failing-root",
                    ],
                },
            ],
        }
    )


    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }


    root_id = (
        tasks["failing-root"]["taskId"]
    )

    child_id = (
        tasks["child"]["taskId"]
    )
    # Attempt 1 fails. With maxAttempts=3,
    # Forge would normally retry after ~5 seconds.
    wait_for_status(
        root_id,
        "FAILED",
    )


    attempts_before = get_attempts(
        root_id
    )


    if len(attempts_before) != 1:
        raise AssertionError(
            "Expected exactly one attempt "
            "before workflow cancellation"
        )


    cancel_workflow(
        workflow["id"]
    )


    wait_for_status(
        root_id,
        "CANCELLED",
    )


    wait_for_status(
        child_id,
        "CANCELLED",
    )


    # Wait past the first retry backoff.
    time.sleep(7)


    attempts_after = get_attempts(
        root_id
    )


    if len(attempts_after) != 1:
        raise AssertionError(
            "Workflow cancellation failed "
            "to suppress automatic retry; "
            f"expected 1 attempt, got "
            f"{len(attempts_after)}"
        )


    persisted = get_workflow(
        workflow["id"]
    )


    if persisted["status"] != "CANCELLED":
        raise AssertionError(
            "Workflow should be CANCELLED, "
            f"got {persisted['status']}"
        )


    print("  PASS")
# ============================================================
# Main
# ============================================================

def main():
    print(
        "Forge DAG smoke tests"
    )

    print(
        "====================="
    )

    # Verify controller is reachable.
    #
    # A 404 here is expected and proves that
    # Spring is listening.
    try:
        urllib.request.urlopen(
            BASE_URL
            + "/does-not-exist",
            timeout=2,
        )

    except urllib.error.HTTPError:
        pass

    except Exception as exc:
        print(
            "\nERROR: Controller is "
            f"not reachable: {exc}"
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
        test_workflow_failure_status,
        test_workflow_cancellation,
        test_workflow_listing,
        test_workflow_retry,
        test_workflow_selective_retry,
        test_worker_listing,
        test_worker_live_load,
        test_workflow_cancel_suppresses_retry,
    ]


    passed = 0


    for test in tests:

        try:
            test()

            passed += 1

        except Exception as exc:
            print(
                f"  FAIL: {exc}"
            )

            print(
                "\n====================="
            )

            print(
                f"{passed}/{len(tests)} "
                "tests passed"
            )

            return 1


    print(
        "\n====================="
    )

    print(
        f"ALL {passed}/{len(tests)} "
        "TESTS PASSED"
    )

    return 0



if __name__ == "__main__":
    sys.exit(
        main()
    )