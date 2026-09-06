#!/usr/bin/env python3

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
import os

TASK_URL = (
    "http://localhost:8080/api/tasks"
)

WORKFLOW_URL = (
    "http://localhost:8080/api/workflows"
)

STATE_FILE = Path(
    "/tmp/forge_restart_smoke_state.json"
)

REPO_ROOT = (
    Path(__file__)
    .resolve()
    .parent
    .parent
)


def request_json(
        method,
        url,
        body=None):

    data = None


    if body is not None:

        data = json.dumps(
            body
        ).encode(
            "utf-8"
        )


    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type":
                "application/json",
        },
    )


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


        return json.loads(
            text
        )


def create_workflow(
        payload):

    return request_json(
        "POST",
        WORKFLOW_URL,
        payload,
    )


def get_workflow(
        workflow_id):

    return request_json(
        "GET",
        f"{WORKFLOW_URL}/{workflow_id}",
    )


def get_task(
        task_id):

    return request_json(
        "GET",
        f"{TASK_URL}/{task_id}",
    )


def get_attempts(
        task_id):

    return request_json(
        "GET",
        f"{TASK_URL}/{task_id}/attempts",
    )


def wait_for_status(
        task_id,
        expected,
        timeout=30):

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
            0.25
        )


    task = get_task(
        task_id
    )


    raise AssertionError(
        f"Expected {task_id} "
        f"to become {expected}, "
        f"got {task['status']}"
    )


def persist_workflow_cancel_intent(
        workflow_id):

    sql = (
        "UPDATE forge_workflows "
        "SET cancel_requested = TRUE "
        f"WHERE id = '{workflow_id}';"
    )


    env = os.environ.copy()

    env["PGPASSWORD"] = "forge"


    subprocess.run(
        [
            "psql",
            "-h",
            "127.0.0.1",
            "-p",
            "5433",
            "-U",
            "forge",
            "-d",
            "forge",
            "-c",
            sql,
        ],
        env=env,
        check=True,
    )

def prepare():

    print(
        "Forge restart durability test"
    )

    print(
        "============================="
    )

    print(
        "\n[PREPARE] creating "
        "in-flight workflow"
    )


    workflow = create_workflow(
        {
            "name":
                "restart-cancellation-test",

            "tasks": [
                {
                    "key":
                        "running-root",

                    "command":
                        "python3",

                    "arguments": [
                        "-c",
                        (
                            "import time; "
                            "print("
                            "'RESTART TEST START', "
                            "flush=True); "
                            "time.sleep(120); "
                            "print("
                            "'RESTART TEST DONE', "
                            "flush=True)"
                        ),
                    ],

                    "maxAttempts":
                        3,

                    "timeoutSeconds":
                        180,
                },
                {
                    "key":
                        "blocked-child",

                    "command":
                        "python3",

                    "arguments": [
                        "-c",
                        (
                            "print("
                            "'ERROR: CHILD EXECUTED', "
                            "flush=True)"
                        ),
                    ],

                    "dependsOn": [
                        "running-root",
                    ],
                },
            ],
        }
    )


    tasks = {
        task["key"]: task
        for task in workflow["tasks"]
    }


    workflow_id = (
        workflow["id"]
    )

    root_id = (
        tasks[
            "running-root"
        ]["taskId"]
    )

    child_id = (
        tasks[
            "blocked-child"
        ]["taskId"]
    )


    print(
        "  workflow:",
        workflow_id,
    )


    print(
        "  waiting for root "
        "to become RUNNING..."
    )


    wait_for_status(
        root_id,
        "RUNNING",
    )


    attempts = get_attempts(
        root_id
    )


    if len(attempts) != 1:

        raise AssertionError(
            "Expected exactly one "
            "physical attempt"
        )


    print(
        "  root RUNNING"
    )

    print(
        "  persisting workflow "
        "cancellation intent directly"
    )


    persist_workflow_cancel_intent(
        workflow_id
    )


    STATE_FILE.write_text(
        json.dumps(
            {
                "workflowId":
                    workflow_id,

                "rootId":
                    root_id,

                "childId":
                    child_id,
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
        "Now IMMEDIATELY:"
    )

    print(
        "  1. Stop the Java controller "
        "with Ctrl+C"
    )

    print(
        "  2. Restart it with:"
    )

    print(
        "     cd ~/forge_app/controller"
    )

    print(
        "     ./gradlew bootRun"
    )

    print(
        "  3. Once Spring has started, run:"
    )

    print(
        "     cd ~/forge_app"
    )

    print(
        "     python3 "
        "scripts/restart_smoke_test.py "
        "verify"
    )


def verify():

    print(
        "Forge restart durability test"
    )

    print(
        "============================="
    )

    print(
        "\n[VERIFY] checking "
        "post-restart state"
    )


    if not STATE_FILE.exists():

        raise RuntimeError(
            "No prepare state found. "
            "Run prepare first."
        )


    state = json.loads(
        STATE_FILE.read_text()
    )


    workflow_id = (
        state["workflowId"]
    )

    root_id = (
        state["rootId"]
    )

    child_id = (
        state["childId"]
    )


    root = wait_for_status(
        root_id,
        "CANCELLED",
        timeout=15,
    )


    child = wait_for_status(
        child_id,
        "CANCELLED",
        timeout=15,
    )


    attempts = get_attempts(
        root_id
    )


    if len(attempts) != 1:

        raise AssertionError(
            "Expected exactly one "
            "root attempt after restart, "
            f"got {len(attempts)}"
        )


    if attempts[0][
            "attemptNumber"
    ] != 1:

        raise AssertionError(
            "Expected only attempt 1"
        )


    if attempts[0][
            "status"
    ] != "LOST":

        raise AssertionError(
            "Interrupted physical attempt "
            "should be LOST after controller "
            "recovery, got "
            f"{attempts[0]['status']}"
        )


    workflow = get_workflow(
        workflow_id
    )


    if workflow["status"] != \
            "CANCELLED":

        raise AssertionError(
            "Workflow should be CANCELLED "
            "after restart recovery, got "
            f"{workflow['status']}"
        )


    print(
        "  workflow: CANCELLED"
    )

    print(
        "  root:     ",
        root["status"],
    )

    print(
        "  child:    ",
        child["status"],
    )

    print(
        "  attempt 1:",
        attempts[0]["status"],
    )
    # Wait beyond Forge's normal first
    # automatic-retry backoff.
    print(
        "  waiting past retry backoff..."
    )


    time.sleep(
        7
    )


    attempts_after = get_attempts(
        root_id
    )


    if len(attempts_after) != 1:

        raise AssertionError(
            "Cancelled workflow resurrected "
            "after restart; expected one "
            "attempt, got "
            f"{len(attempts_after)}"
        )


    root_after = get_task(
        root_id
    )

    child_after = get_task(
        child_id
    )


    if root_after["status"] != \
            "CANCELLED":

        raise AssertionError(
            "Root changed after recovery: "
            f"{root_after['status']}"
        )


    if child_after["status"] != \
            "CANCELLED":

        raise AssertionError(
            "Child changed after recovery: "
            f"{child_after['status']}"
        )


    print()
    print(
        "RESTART DURABILITY PASS"
    )

    print(
        "Workflow cancellation survived "
        "controller restart and suppressed "
        "execution/retry."
    )


def main():

    if len(sys.argv) != 2:

        print(
            "Usage:"
        )

        print(
            "  python3 "
            "scripts/restart_smoke_test.py "
            "prepare"
        )

        print(
            "  python3 "
            "scripts/restart_smoke_test.py "
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