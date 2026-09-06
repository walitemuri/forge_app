#!/usr/bin/env python3

import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


BASE = "http://localhost:8080"

WORKERS_URL = f"{BASE}/api/workers"
TASKS_URL = f"{BASE}/api/tasks"

REPO_ROOT = (
    Path(__file__)
    .resolve()
    .parent
    .parent
)

WORKER_BINARY = (
    REPO_ROOT
    / "worker"
    / "build"
    / "forge-worker"
)

SECONDARY_LOG = Path(
    "/tmp/forge_worker_fencing_secondary.log"
)

POLL_INTERVAL = 0.20


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
            "Content-Type":
                "application/json",
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

            return json.loads(
                text
            )

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
# Worker API
# ============================================================

def connected_workers():

    return [
        worker
        for worker in get(
            WORKERS_URL
        )
        if worker["online"]
        and worker[
            "commandStreamConnected"
        ]
    ]


def require_one_worker():

    workers = connected_workers()

    if len(workers) != 1:

        raise AssertionError(
            "Expected exactly one "
            "connected worker, got "
            f"{len(workers)}"
        )

    return workers[0]


def wait_for_session(
        worker_id,
        predicate,
        timeout=20):

    deadline = (
        time.time()
        + timeout
    )

    while time.time() < deadline:

        workers = get(
            WORKERS_URL
        )

        matches = [
            worker
            for worker in workers
            if worker["id"]
                == worker_id
        ]

        if len(matches) == 1:

            worker = matches[0]

            if predicate(worker):
                return worker

        time.sleep(
            POLL_INTERVAL
        )


    raise AssertionError(
        "Timed out waiting for "
        "worker session state"
    )


# ============================================================
# Processes
# ============================================================

def local_worker_pids():

    result = subprocess.run(
        [
            "pgrep",
            "-x",
            "forge-worker",
        ],
        text=True,
        capture_output=True,
    )

    if result.returncode != 0:
        return []


    return [
        int(line)
        for line in (
            result.stdout
            .strip()
            .splitlines()
        )
        if line.strip()
    ]


def require_one_local_worker():

    pids = local_worker_pids()

    if len(pids) != 1:

        raise AssertionError(
            "Expected exactly one local "
            "forge-worker process, got "
            f"{pids}"
        )

    return pids[0]


def read_process_environment(pid):

    data = Path(
        f"/proc/{pid}/environ"
    ).read_bytes()

    environment = {}

    for entry in data.split(b"\0"):

        if not entry:
            continue

        decoded = entry.decode(
            "utf-8",
            errors="replace",
        )

        if "=" not in decoded:
            continue

        key, value = decoded.split(
            "=",
            1,
        )

        environment[key] = value


    return environment


def worker_outbox(
        worker_id,
        environment):

    configured = environment.get(
        "FORGE_OUTBOX_DIR"
    )

    if configured:

        root = Path(
            configured
        )

    else:

        home = environment.get(
            "HOME",
            str(Path.home()),
        )

        root = (
            Path(home)
            / ".forge"
            / "outbox"
        )


    return (
        root
        / worker_id
    )


def launch_worker(environment):

    if not WORKER_BINARY.exists():

        raise AssertionError(
            "Worker binary not found: "
            f"{WORKER_BINARY}"
        )


    SECONDARY_LOG.unlink(
        missing_ok=True
    )


    log = open(
        SECONDARY_LOG,
        "ab",
        buffering=0,
    )


    process = subprocess.Popen(
        [
            str(WORKER_BINARY),
        ],
        cwd=str(
            WORKER_BINARY.parent
        ),
        env=environment,
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )


    log.close()

    return process


def wait_for_log(
        text,
        timeout=10):

    deadline = (
        time.time()
        + timeout
    )

    while time.time() < deadline:

        if SECONDARY_LOG.exists():

            contents = (
                SECONDARY_LOG
                .read_text(
                    errors="replace"
                )
            )

            if text in contents:
                return contents

        time.sleep(
            POLL_INTERVAL
        )


    raise AssertionError(
        "Did not observe expected worker "
        f"log message: {text}"
    )


# ============================================================
# Tasks
# ============================================================

def get_task(task_id):

    return get(
        f"{TASKS_URL}/{task_id}"
    )


def wait_task(
        task_id,
        expected,
        timeout=15):

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


# ============================================================
# Test
# ============================================================

def main():

    if not sys.platform.startswith(
            "linux"):

        raise RuntimeError(
            "This test currently "
            "requires Linux/WSL"
        )


    print(
        "Forge worker session fencing test"
    )

    print(
        "================================="
    )


    # --------------------------------------------------------
    # Existing authoritative worker A
    # --------------------------------------------------------

    worker_a = require_one_worker()

    worker_id = worker_a["id"]

    session_a = worker_a["sessionId"]

    pid_a = require_one_local_worker()


    print(
        "Worker:",
        worker_id,
    )

    print(
        "Session A:",
        session_a,
    )

    print(
        "PID A:",
        pid_a,
    )


    environment = (
        read_process_environment(
            pid_a
        )
    )


    outbox = worker_outbox(
        worker_id,
        environment,
    )


    pending_files = list(
        outbox.glob(
            "*.event"
        )
    )


    if pending_files:

        raise AssertionError(
            "Worker outbox must be empty "
            "before fencing test. Found: "
            f"{pending_files}"
        )


    # --------------------------------------------------------
    # Start competing process B
    # --------------------------------------------------------

    print(
        "\n[START] launching second "
        "worker with same worker ID"
    )


    worker_b_process = (
        launch_worker(
            environment
        )
    )


    pid_b = (
        worker_b_process.pid
    )


    print(
        "PID B:",
        pid_b,
    )


    print(
        "[WAIT] waiting for duplicate "
        "registration rejection"
    )


    wait_for_log(
        "registration failed",
        timeout=10,
    )


    if worker_b_process.poll() \
            is not None:

        raise AssertionError(
            "Secondary worker exited "
            "instead of retrying"
        )


    # --------------------------------------------------------
    # A must remain authoritative
    # --------------------------------------------------------

    current = wait_for_session(
        worker_id,
        lambda worker:
            worker["sessionId"]
                == session_a
            and worker["online"]
            and worker[
                "commandStreamConnected"
            ],
        timeout=5,
    )


    if current["sessionId"] \
            != session_a:

        raise AssertionError(
            "Secondary worker stole "
            "worker ownership"
        )


    pids = local_worker_pids()

    if pid_a not in pids \
            or pid_b not in pids:

        raise AssertionError(
            "Expected both worker "
            "processes to still be alive"
        )


    print(
        "[VERIFY] session B fenced; "
        "session A still authoritative"
    )


    # --------------------------------------------------------
    # Kill A
    # --------------------------------------------------------

    print(
        "[FAILOVER] SIGKILL session A"
    )


    os.kill(
        pid_a,
        signal.SIGKILL,
    )


    # --------------------------------------------------------
    # B must eventually acquire ownership
    # --------------------------------------------------------

    print(
        "[WAIT] session B retrying "
        "registration"
    )


    worker_b = wait_for_session(
        worker_id,
        lambda worker:
            worker["sessionId"]
                != session_a
            and worker["online"]
            and worker[
                "commandStreamConnected"
            ],
        timeout=20,
    )


    session_b = (
        worker_b["sessionId"]
    )


    if not session_b:

        raise AssertionError(
            "Replacement session ID "
            "is empty"
        )


    print(
        "Session B:",
        session_b,
    )


    print(
        "[VERIFY] session B became "
        "authoritative"
    )


    # --------------------------------------------------------
    # New work must go through B
    # --------------------------------------------------------

    print(
        "[TASK] verifying replacement "
        "worker accepts new work"
    )


    task = post(
        TASKS_URL,
        {
            "command": "python3",
            "arguments": [
                "-c",
                (
                    "print("
                    "'session-fencing-ok'"
                    ")"
                ),
            ],
            "maxAttempts": 1,
            "timeoutSeconds": 10,
        },
    )


    completed = wait_task(
        task["id"],
        "SUCCEEDED",
        timeout=15,
    )


    if (
        "session-fencing-ok"
        not in (
            completed.get("stdout")
            or ""
        )
    ):

        raise AssertionError(
            "Replacement worker task "
            "output missing"
        )


    # --------------------------------------------------------
    # Final process state
    # --------------------------------------------------------

    deadline = (
        time.time()
        + 5
    )


    while time.time() < deadline:

        pids = local_worker_pids()

        if pids == [pid_b]:
            break

        if (
            len(pids) == 1
            and pids[0] == pid_b
        ):
            break

        time.sleep(
            POLL_INTERVAL
        )


    pids = local_worker_pids()


    if len(pids) != 1 \
            or pids[0] != pid_b:

        raise AssertionError(
            "Expected only replacement "
            f"worker {pid_b}, got {pids}"
        )


    print()
    print(
        "WORKER SESSION FENCING PASS"
    )

    print(
        "A second live process could not "
        "steal the worker ID; after the "
        "authoritative process died, the "
        "fenced process retried, took "
        "ownership, and executed new work."
    )

    print()
    print(
        "Replacement worker remains "
        "running as PID",
        pid_b,
    )

    print(
        "Worker log:",
        SECONDARY_LOG,
    )


if __name__ == "__main__":

    try:

        main()

    except Exception as exc:

        print(
            f"\nFAIL: {exc}"
        )

        sys.exit(1)
