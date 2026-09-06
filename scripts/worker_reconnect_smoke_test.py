#!/usr/bin/env python3

import json
import sys
import time
import urllib.request
from pathlib import Path


WORKERS_URL = "http://localhost:8080/api/workers"

STATE_FILE = Path(
    "/tmp/forge_worker_reconnect_state.json"
)


def get_workers():
    with urllib.request.urlopen(
            WORKERS_URL,
            timeout=5) as response:

        return json.loads(
            response
            .read()
            .decode("utf-8")
        )


def prepare():
    print("Forge worker reconnect test")
    print("===========================")

    workers = get_workers()

    connected = [
        worker
        for worker in workers
        if worker["online"]
        and worker["commandStreamConnected"]
    ]

    if not connected:
        raise AssertionError(
            "No online worker with a command stream"
        )

    worker = connected[0]

    STATE_FILE.write_text(
        json.dumps(
            {
                "workerId": worker["id"],
            },
            indent=2,
        )
    )

    print()
    print("PREPARE PASS")
    print("Worker:", worker["id"])

    print()
    print("Now:")
    print("  1. Leave the worker running")
    print("  2. Stop ONLY the Java controller with Ctrl+C")
    print("  3. Restart it:")
    print("     cd ~/forge_app/controller")
    print("     ./gradlew bootRun")
    print("  4. Then run:")
    print(
        "     python3 "
        "scripts/worker_reconnect_smoke_test.py verify"
    )


def verify():
    print("Forge worker reconnect test")
    print("===========================")

    if not STATE_FILE.exists():
        raise AssertionError(
            "Run prepare first"
        )

    worker_id = json.loads(
        STATE_FILE.read_text()
    )["workerId"]

    print()
    print(
        "Waiting for worker to reconnect:",
        worker_id,
    )

    deadline = time.time() + 30

    while time.time() < deadline:
        try:
            workers = get_workers()
        except Exception:
            time.sleep(1)
            continue

        for worker in workers:
            if worker["id"] != worker_id:
                continue

            if (
                worker["online"]
                and worker["commandStreamConnected"]
            ):
                print()
                print("WORKER RECONNECT PASS")
                print(
                    "Worker automatically re-registered "
                    "and restored its command stream."
                )
                return

        time.sleep(1)

    raise AssertionError(
        "Worker did not automatically reconnect "
        "within 30 seconds"
    )


def main():
    if len(sys.argv) != 2:
        print(
            "Usage: "
            "worker_reconnect_smoke_test.py "
            "prepare|verify"
        )
        return 1

    try:
        if sys.argv[1] == "prepare":
            prepare()

        elif sys.argv[1] == "verify":
            verify()

        else:
            raise ValueError(
                "Mode must be prepare or verify"
            )

        return 0

    except Exception as exc:
        print()
        print("FAIL:", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
