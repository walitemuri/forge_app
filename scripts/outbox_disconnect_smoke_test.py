#!/usr/bin/env python3

import json
import subprocess
import time
import urllib.request


BASE = "http://localhost:8080"


def request(method, path, body=None):
    data = None

    if body is not None:
        data = json.dumps(body).encode("utf-8")

    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
        },
    )

    with urllib.request.urlopen(
        req,
        timeout=5,
    ) as response:
        return json.loads(
            response.read().decode("utf-8")
        )


def get(path):
    return request("GET", path)


def post(path, body=None):
    return request(
        "POST",
        path,
        body,
    )


def wait_for_task(task_id, wanted, timeout=20):
    deadline = time.time() + timeout

    while time.time() < deadline:
        task = get(
            f"/api/tasks/{task_id}"
        )

        if task["status"] == wanted:
            return task

        time.sleep(0.25)

    raise AssertionError(
        f"task {task_id} never reached {wanted}"
    )


def run(command):
    subprocess.run(
        command,
        check=True,
    )


def add_network_block():
    print(
        "[CHAOS] blocking worker -> controller gRPC"
    )

    run([
        "sudo",
        "iptables",
        "-I",
        "OUTPUT",
        "1",
        "-p",
        "tcp",
        "--dport",
        "50051",
        "-j",
        "REJECT",
    ])

    try:
        run([
            "sudo",
            "ip6tables",
            "-I",
            "OUTPUT",
            "1",
            "-p",
            "tcp",
            "--dport",
            "50051",
            "-j",
            "REJECT",
        ])
    except Exception:
        print(
            "[CHAOS] IPv6 rule unavailable; "
            "continuing with IPv4 rule"
        )


def remove_network_block():
    print(
        "[CHAOS] restoring gRPC network"
    )

    subprocess.run([
        "sudo",
        "iptables",
        "-D",
        "OUTPUT",
        "-p",
        "tcp",
        "--dport",
        "50051",
        "-j",
        "REJECT",
    ])

    subprocess.run([
        "sudo",
        "ip6tables",
        "-D",
        "OUTPUT",
        "-p",
        "tcp",
        "--dport",
        "50051",
        "-j",
        "REJECT",
    ])


def main():
    print(
        "Forge outbox disconnect test"
    )
    print(
        "============================"
    )

    workers = get(
        "/api/workers"
    )

    connected = [
        worker
        for worker in workers
        if worker["online"]
        and worker["commandStreamConnected"]
    ]

    if not connected:
        raise AssertionError(
            "No connected worker"
        )

    print(
        "Worker:",
        connected[0]["id"],
    )


    print(
        "[CREATE] submitting task"
    )

    task = post(
        "/api/tasks",
        {
            "command": "python3",
            "arguments": [
                "-c",
                (
                    "import time; "
                    "time.sleep(4); "
                    "print('outbox-replay-ok')"
                ),
            ],
            "maxAttempts": 1,
            "timeoutSeconds": 20,
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

    wait_for_task(
        task_id,
        "RUNNING",
    )


    print(
        "[VERIFY] task is RUNNING"
    )


    blocked = False

    try:
        add_network_block()
        blocked = True

        # The task sleeps for four seconds.
        # Keeping gRPC blocked for six seconds means
        # TaskResult must be created while the worker
        # cannot deliver it.
        time.sleep(6)

    finally:
        if blocked:
            remove_network_block()


    print(
        "[WAIT] allowing worker to reconnect/replay"
    )

    task = wait_for_task(
        task_id,
        "SUCCEEDED",
        timeout=25,
    )


    if "outbox-replay-ok" not in (
        task.get("stdout") or ""
    ):
        raise AssertionError(
            "expected stdout missing"
        )


    attempts = get(
        f"/api/tasks/{task_id}/attempts"
    )


    if len(attempts) != 1:
        raise AssertionError(
            "expected exactly one physical attempt, "
            f"got {len(attempts)}"
        )


    if attempts[0]["status"] != "SUCCEEDED":
        raise AssertionError(
            "attempt did not finish SUCCEEDED: "
            + attempts[0]["status"]
        )


    print()
    print(
        "OUTBOX DISCONNECT PASS"
    )

    print(
        "Task completed while gRPC was unavailable, "
        "the result survived in the worker outbox, "
        "and was replayed after reconnect."
    )


if __name__ == "__main__":
    main()
