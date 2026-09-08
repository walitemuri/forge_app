#!/usr/bin/env python3

import json
import os
import signal
import time
import urllib.request
import uuid
from pathlib import Path


CONTROLLER_URL = os.environ.get(
    "FORGE_CONTROLLER_URL",
    "http://127.0.0.1:8080",
)

WORKFLOWS_URL = f"{CONTROLLER_URL}/api/workflows"


def get_json(url):
    with urllib.request.urlopen(
        url,
        timeout=10,
    ) as response:
        return json.load(response)


def post_json(url, body):
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
        },
    )

    with urllib.request.urlopen(
        request,
        timeout=15,
    ) as response:
        return json.load(response)


def shell(
    command,
    *,
    max_attempts=1,
    timeout_seconds=120,
):
    return {
        "command": "bash",
        "arguments": [
            "-lc",
            command,
        ],
        "maxAttempts": max_attempts,
        "timeoutSeconds": timeout_seconds,
    }


def find_worker_pid(worker_id):
    expected = f"FORGE_WORKER_ID={worker_id}".encode()

    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue

        try:
            environ = (proc / "environ").read_bytes()
            exe = os.readlink(proc / "exe")
        except (
            FileNotFoundError,
            PermissionError,
            ProcessLookupError,
        ):
            continue

        variables = environ.split(b"\0")

        if (
            expected in variables
            and "forge-worker" in exe
        ):
            return int(proc.name)

    return None


def task_key_map(workflow):
    return {
        task["taskId"]: task["key"]
        for task in workflow["tasks"]
    }


def wait_for_running_attempt(
    workflow_id,
    task_key,
    timeout=30,
):
    deadline = time.monotonic() + timeout

    detail_url = (
        f"{WORKFLOWS_URL}/{workflow_id}"
    )

    events_url = (
        f"{detail_url}/events"
    )

    while time.monotonic() < deadline:
        workflow = get_json(detail_url)
        keys = task_key_map(workflow)
        events = get_json(events_url)

        for event in reversed(events):
            key = keys.get(
                event.get("taskId")
            )

            if (
                key == task_key
                and event["type"] == "ATTEMPT_RUNNING"
                and event.get("workerId")
            ):
                return {
                    "attemptId": event["attemptId"],
                    "workerId": event["workerId"],
                }

        time.sleep(0.25)

    raise RuntimeError(
        "Timed out waiting for "
        f"{task_key} to start"
    )


def wait_for_terminal(
    workflow_id,
    timeout=120,
):
    deadline = time.monotonic() + timeout

    url = (
        f"{WORKFLOWS_URL}/{workflow_id}"
    )

    terminal = {
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
    }

    while time.monotonic() < deadline:
        workflow = get_json(url)

        if workflow["status"] in terminal:
            return workflow

        time.sleep(1)

    raise RuntimeError(
        "Timed out waiting for workflow "
        "to finish"
    )


def print_recovery_timeline(
    workflow_id,
):
    workflow = get_json(
        f"{WORKFLOWS_URL}/{workflow_id}"
    )

    events = get_json(
        f"{WORKFLOWS_URL}/{workflow_id}/events"
    )

    keys = task_key_map(workflow)

    interesting = {
        "ATTEMPT_CREATED",
        "TASK_DISPATCHED",
        "ATTEMPT_DISPATCHED",
        "ATTEMPT_RUNNING",
        "TASK_RUNNING",
        "ATTEMPT_LOST",
        "TASK_LOST",
        "TASK_PENDING",
        "ATTEMPT_SUCCEEDED",
        "TASK_SUCCEEDED",
    }

    print()
    print("Recovery timeline:")
    print()

    for event in events:
        key = keys.get(
            event.get("taskId")
        )

        if (
            key == "resilient-task"
            and event["type"] in interesting
        ):
            print(
                "{:<22} {:<18} {}".format(
                    event["type"],
                    event.get("workerId") or "-",
                    event["message"],
                )
            )


def main():
    online_workers = [
        worker
        for worker in get_json(
            f"{CONTROLLER_URL}/api/workers"
        )
        if (
            worker["online"]
            and worker["commandStreamConnected"]
        )
    ]

    if len(online_workers) < 2:
        raise RuntimeError(
            "Need at least two online workers "
            "for recovery demo"
        )

    run_id = uuid.uuid4().hex[:8]

    name = (
        f"worker-loss-recovery-{run_id}"
    )

    workflow = {
        "name": name,

        "tasks": [
            {
                "key": "prepare",

                **shell(
                    """
set -euo pipefail

echo "Preparing worker-loss demo"

uname -a

echo "Preparation complete"
"""
                ),

                "dependsOn": [],
            },

            {
                "key": "resilient-task",

                **shell(
                    """
set -euo pipefail

echo "Long-running verification started"

# Long enough for the harness to kill
# the worker while this attempt is active.
sleep 30

echo "Worker survived execution window"
echo "Verification complete"
""",
                    max_attempts=2,
                    timeout_seconds=90,
                ),

                "dependsOn": [
                    "prepare",
                ],
            },

            {
                "key": "release-gate",

                **shell(
                    """
set -euo pipefail

echo "Recovered execution succeeded"
echo "Release gate passed"
"""
                ),

                "dependsOn": [
                    "resilient-task",
                ],
            },
        ],
    }

    print(
        "Submitting:",
        name,
    )

    result = post_json(
        WORKFLOWS_URL,
        workflow,
    )

    workflow_id = result["id"]

    print()
    print(
        "Workflow:",
        workflow_id,
    )

    print()
    print("Dashboard:")
    print(
        "http://localhost:3000"
        f"/workflows/{workflow_id}"
    )

    print()
    print(
        "Waiting for resilient-task "
        "to start..."
    )

    running = wait_for_running_attempt(
        workflow_id,
        "resilient-task",
    )

    worker_id = running["workerId"]
    attempt_id = running["attemptId"]

    print(
        f"Attempt {attempt_id[:8]} "
        f"is running on {worker_id}"
    )

    pid = find_worker_pid(
        worker_id
    )

    if pid is None:
        raise RuntimeError(
            "Could not locate local process "
            f"for {worker_id}"
        )

    print()
    print(
        f"Killing {worker_id} "
        f"(PID {pid})..."
    )

    os.kill(
        pid,
        signal.SIGKILL,
    )

    print("Worker terminated.")

    print()
    print(
        "Waiting for heartbeat timeout, "
        "LOST detection, and retry..."
    )

    final = wait_for_terminal(
        workflow_id,
    )

    print()
    print(
        "Final workflow status:",
        final["status"],
    )

    print_recovery_timeline(
        workflow_id,
    )


if __name__ == "__main__":
    main()
