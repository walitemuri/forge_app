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
TASK_URL = f"{BASE}/api/tasks"
WORKER_URL = f"{BASE}/api/workers"

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

RESTART_LOG = Path(
    "/tmp/forge_worker_crash_restart.log"
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


def connected_worker():

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

    if len(connected) != 1:
        raise AssertionError(
            "This test expects exactly one "
            "connected worker, got "
            f"{len(connected)}"
        )

    return connected[0]


# ============================================================
# Worker process helpers
# ============================================================

def find_worker_pid():

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
        raise AssertionError(
            "Could not find forge-worker process"
        )

    pids = [
        int(line)
        for line in (
            result.stdout
            .strip()
            .splitlines()
        )
        if line.strip()
    ]

    if len(pids) != 1:
        raise AssertionError(
            "Expected exactly one local "
            "forge-worker process, got "
            f"{pids}"
        )

    return pids[0]


def read_process_environment(pid):

    path = Path(
        f"/proc/{pid}/environ"
    )

    data = path.read_bytes()

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


def outbox_directory(
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

    return root / worker_id


def safe_filename(event_id):

    return "".join(
        character
        if (
            character.isalnum()
            or character in "-_."
        )
        else "_"
        for character in event_id
    )


def restart_worker(environment):

    if not WORKER_BINARY.exists():

        raise AssertionError(
            "Worker binary not found: "
            f"{WORKER_BINARY}"
        )

    log = open(
        RESTART_LOG,
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


# ============================================================
# Network chaos
# ============================================================

IPTABLES_RULE = [
    "-p",
    "tcp",
    "--dport",
    "50051",
    "-j",
    "REJECT",
]


def authenticate_sudo():

    subprocess.run(
        [
            "sudo",
            "-v",
        ],
        check=True,
    )


def block_grpc():

    subprocess.run(
        [
            "sudo",
            "iptables",
            "-I",
            "OUTPUT",
            "1",
            *IPTABLES_RULE,
        ],
        check=True,
    )

    try:
        subprocess.run(
            [
                "sudo",
                "ip6tables",
                "-I",
                "OUTPUT",
                "1",
                *IPTABLES_RULE,
            ],
            check=True,
        )

    except subprocess.CalledProcessError:
        pass


def restore_grpc():

    subprocess.run(
        [
            "sudo",
            "iptables",
            "-D",
            "OUTPUT",
            *IPTABLES_RULE,
        ],
        check=False,
    )

    subprocess.run(
        [
            "sudo",
            "ip6tables",
            "-D",
            "OUTPUT",
            *IPTABLES_RULE,
        ],
        check=False,
    )


# ============================================================
# Main test
# ============================================================

def main():

    if not sys.platform.startswith(
            "linux"):

        raise RuntimeError(
            "This chaos test currently "
            "requires Linux/WSL"
        )

    print(
        "Forge durable worker outbox test"
    )

    print(
        "================================"
    )

    worker = connected_worker()

    worker_id = worker["id"]

    pid = find_worker_pid()

    environment = (
        read_process_environment(
            pid
        )
    )

    outbox = outbox_directory(
        worker_id,
        environment,
    )

    print(
        "Worker:",
        worker_id,
    )

    print(
        "PID:",
        pid,
    )

    print(
        "Outbox:",
        outbox,
    )


    authenticate_sudo()


    print(
        "\n[CREATE] submitting task"
    )

    created = post(
        TASK_URL,
        {
            "command": "python3",
            "arguments": [
                "-c",
                (
                    "import time; "
                    "time.sleep(4); "
                    "print("
                    "'disk-outbox-replay-ok', "
                    "flush=True)"
                ),
            ],
            "maxAttempts": 1,
            "timeoutSeconds": 20,
        },
    )

    task_id = created["id"]

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
            "Expected one attempt"
        )

    attempt_id = attempts[0]["id"]

    event_id = (
        attempt_id
        + ":result"
    )

    result_file = (
        outbox
        / (
            safe_filename(
                event_id
            )
            + ".event"
        )
    )


    blocked = False

    try:

        print(
            "[CHAOS] blocking worker "
            "-> controller gRPC"
        )

        block_grpc()

        blocked = True


        print(
            "[WAIT] waiting for result "
            "to be persisted to disk"
        )

        deadline = (
            time.time()
            + 10
        )

        while (
            time.time() < deadline
            and not result_file.exists()
        ):
            time.sleep(
                POLL_INTERVAL
            )


        if not result_file.exists():

            raise AssertionError(
                "Result was not persisted "
                "to worker outbox:\n"
                f"{result_file}"
            )


        size = result_file.stat().st_size

        if size <= 0:

            raise AssertionError(
                "Persisted event file is empty"
            )


        print(
            "[VERIFY] durable result exists:"
        )

        print(
            " ",
            result_file,
        )


        print(
            "[CRASH] SIGKILL worker PID",
            pid,
        )

        os.kill(
            pid,
            signal.SIGKILL,
        )


        time.sleep(
            0.5
        )


    finally:

        if blocked:

            print(
                "[CHAOS] restoring gRPC"
            )

            restore_grpc()


    print(
        "[RESTART] starting fresh "
        "worker process"
    )

    replacement = restart_worker(
        environment
    )

    print(
        "New PID:",
        replacement.pid,
    )


    print(
        "[WAIT] waiting for persisted "
        "result replay"
    )

    completed = wait_for_status(
        task_id,
        "SUCCEEDED",
        timeout=12,
    )


    if (
        "disk-outbox-replay-ok"
        not in (
            completed.get("stdout")
            or ""
        )
    ):

        raise AssertionError(
            "Replayed result stdout "
            "is missing"
        )


    attempts_after = get_attempts(
        task_id
    )

    if len(attempts_after) != 1:

        raise AssertionError(
            "Expected original attempt "
            "to finish without retry; got "
            f"{len(attempts_after)} attempts"
        )


    if attempts_after[0]["id"] \
            != attempt_id:

        raise AssertionError(
            "Attempt identity changed"
        )


    if attempts_after[0]["status"] \
            != "SUCCEEDED":

        raise AssertionError(
            "Original attempt did not "
            "become SUCCEEDED"
        )


    deadline = (
        time.time()
        + 5
    )

    while (
        time.time() < deadline
        and result_file.exists()
    ):
        time.sleep(
            POLL_INTERVAL
        )


    if result_file.exists():

        raise AssertionError(
            "ACK did not remove durable "
            "outbox file"
        )


    event_types = [
        event["type"]
        for event in get_events(
            task_id
        )
    ]


    if "ATTEMPT_LOST" in event_types \
            or "TASK_LOST" in event_types:

        raise AssertionError(
            "Controller declared work LOST "
            "before durable replay completed"
        )


    print()
    print(
        "DURABLE OUTBOX CRASH PASS"
    )

    print(
        "Result survived SIGKILL, "
        "was loaded by a fresh worker "
        "process, replayed to the "
        "controller, ACKed, and removed "
        "from disk."
    )

    print()
    print(
        "Replacement worker is still "
        "running as PID",
        replacement.pid,
    )

    print(
        "Worker log:",
        RESTART_LOG,
    )


if __name__ == "__main__":

    try:
        main()

    except Exception as exc:

        print(
            f"\nFAIL: {exc}"
        )

        sys.exit(1)
