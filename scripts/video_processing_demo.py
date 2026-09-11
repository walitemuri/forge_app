#!/usr/bin/env python3

import argparse
import json
import math
import os
import shlex
import subprocess
import time
import urllib.request
import uuid
from pathlib import Path


CONTROLLER_URL = os.environ.get(
    "FORGE_CONTROLLER_URL",
    "http://127.0.0.1:8080",
)

WORKFLOW_URL = (
    f"{CONTROLLER_URL}/api/workflows"
)

WORKER_URL = (
    f"{CONTROLLER_URL}/api/workers"
)


def request_json(
    method,
    url,
    body=None,
):
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

    with urllib.request.urlopen(
        request,
        timeout=15,
    ) as response:
        text = (
            response
            .read()
            .decode("utf-8")
        )

        if not text:
            return None

        return json.loads(text)


def get_json(url):
    return request_json(
        "GET",
        url,
    )


def post_json(
    url,
    body,
):
    return request_json(
        "POST",
        url,
        body,
    )


def quote(value):
    return shlex.quote(
        str(value)
    )


def shell(
    key,
    command,
    dependencies,
    *,
    max_attempts=1,
    timeout_seconds=300,
):
    return {
        "key": key,

        "command": "bash",

        "arguments": [
            "-lc",
            command,
        ],

        "maxAttempts":
            max_attempts,

        "timeoutSeconds":
            timeout_seconds,

        "dependsOn":
            dependencies,
    }


def probe_duration(
    source,
):
    output = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default="
            "noprint_wrappers=1:"
            "nokey=1",
            str(source),
        ],
        text=True,
    )

    return float(
        output.strip()
    )


def require_workers(
    minimum=3,
):
    workers = get_json(
        WORKER_URL
    )

    online = [
        worker
        for worker in workers
        if (
            worker.get("online")
            and
            worker.get(
                "commandStreamConnected"
            )
        )
    ]

    if len(online) < minimum:
        raise RuntimeError(
            f"Need at least {minimum} "
            "online workers; "
            f"found {len(online)}"
        )

    print("Workers:")

    for worker in online:
        print(
            "  {:<20} capacity={}".format(
                worker["id"],
                worker["capacity"],
            )
        )

    print()


def wait_for_terminal(
    workflow_id,
    timeout=900,
):
    deadline = (
        time.monotonic()
        + timeout
    )

    terminal = {
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
    }

    while time.monotonic() < deadline:
        workflow = get_json(
            f"{WORKFLOW_URL}"
            f"/{workflow_id}"
        )

        status = workflow["status"]

        print(
            f"\rWorkflow status: "
            f"{status:<12}",
            end="",
            flush=True,
        )

        if status in terminal:
            print()
            return workflow

        time.sleep(1)

    print()

    raise RuntimeError(
        "Timed out waiting for workflow"
    )


def print_worker_assignments(
    workflow_id,
):
    workflow = get_json(
        f"{WORKFLOW_URL}"
        f"/{workflow_id}"
    )

    events = get_json(
        f"{WORKFLOW_URL}"
        f"/{workflow_id}"
        "/events"
    )

    keys = {
        task["taskId"]:
            task["key"]
        for task in workflow["tasks"]
    }

    print()
    print("Worker assignments:")
    print()

    for event in events:
        if (
            event["type"]
            == "TASK_DISPATCHED"
            and
            event.get("workerId")
        ):
            key = keys.get(
                event.get("taskId"),
                "unknown",
            )

            print(
                "  {:<20} -> {}".format(
                    key,
                    event["workerId"],
                )
            )


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Run a distributed FFmpeg "
            "video-processing workload "
            "through Forge."
        )
    )

    parser.add_argument(
        "--source",
        default=(
            "demo/video/input/"
            "source.mp4"
        ),
    )

    parser.add_argument(
        "--chunk-seconds",
        type=float,
        default=5.0,
    )

    args = parser.parse_args()

    source = (
        Path(args.source)
        .expanduser()
        .resolve()
    )

    if not source.is_file():
        raise SystemExit(
            f"Source video not found: "
            f"{source}"
        )

    if args.chunk_seconds <= 0:
        raise SystemExit(
            "--chunk-seconds must be > 0"
        )

    require_workers(3)

    duration = probe_duration(
        source
    )

    chunk_count = math.ceil(
        duration
        / args.chunk_seconds
    )

    run_id = (
        uuid.uuid4()
        .hex[:8]
    )

    repo_root = (
        Path(__file__)
        .resolve()
        .parent
        .parent
    )

    run_root = (
        repo_root
        / "demo"
        / "video"
        / "runs"
        / run_id
    )

    processed_dir = (
        run_root
        / "processed"
    )

    output_dir = (
        run_root
        / "output"
    )

    processed_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    output_dir.mkdir(
        parents=True,
        exist_ok=True,
    )

    source_q = quote(source)

    processed_q = quote(
        processed_dir
    )

    output_q = quote(
        output_dir
    )

    workflow_name = (
        "distributed-video-"
        f"{run_id}"
    )

    tasks = []

    tasks.append(
        shell(
            "inspect-video",
            f"""
set -euo pipefail

echo "=== Source video ==="

ffprobe \
  -v error \
  -show_entries \
  format=duration,size,bit_rate \
  -show_entries \
  stream=index,codec_name,codec_type,width,height \
  -of default=noprint_wrappers=1 \
  {source_q}

mkdir -p \
  {processed_q} \
  {output_q}

echo
echo "Video validated"
""",
            [],
            timeout_seconds=30,
        )
    )

    transcode_keys = []

    for index in range(
        chunk_count
    ):
        start = (
            index
            * args.chunk_seconds
        )

        remaining = (
            duration - start
        )

        segment_duration = min(
            args.chunk_seconds,
            remaining,
        )

        key = (
            f"transcode-{index:03d}"
        )

        transcode_keys.append(
            key
        )

        output_file = (
            processed_dir
            / f"chunk_{index:03d}.mp4"
        )

        output_q_file = quote(
            output_file
        )

        command = f"""
set -euo pipefail

echo "=================================="
echo " Segment {index:03d}"
echo " Start:    {start:.3f}s"
echo " Duration: {segment_duration:.3f}s"
echo "=================================="

ffmpeg \
  -y \
  -hide_banner \
  -loglevel warning \
  -ss {start:.6f} \
  -i {source_q} \
  -t {segment_duration:.6f} \
  -vf "scale=960:540,eq=contrast=1.12:saturation=1.35" \
  -c:v libx264 \
  -preset slow \
  -crf 21 \
  -threads 2 \
  -c:a aac \
  -b:a 128k \
  {output_q_file}

test -s \
  {output_q_file}

echo
echo "Segment {index:03d} complete"
"""

        tasks.append(
            shell(
                key,
                command,
                [
                    "inspect-video",
                ],
                max_attempts=2,
                timeout_seconds=300,
            )
        )

    final_output = (
        output_dir
        / "forge-output.mp4"
    )

    final_output_q = quote(
        final_output
    )

    merge_command = f"""
set -euo pipefail

cd {processed_q}

rm -f concat.txt

for file in chunk_*.mp4; do
    printf "file '%s'\\n" "$file"
done > concat.txt

echo "=== Merge manifest ==="
cat concat.txt

ffmpeg \
  -y \
  -hide_banner \
  -loglevel warning \
  -f concat \
  -safe 0 \
  -i concat.txt \
  -c copy \
  -movflags +faststart \
  {final_output_q}

test -s \
  {final_output_q}

echo
echo "=== Final output ==="

ffprobe \
  -v error \
  -show_entries \
  format=duration,size,bit_rate \
  -of default=noprint_wrappers=1 \
  {final_output_q}

echo
echo "Distributed video processing complete"
"""

    tasks.append(
        shell(
            "merge-video",
            merge_command,
            transcode_keys,
            timeout_seconds=120,
        )
    )

    payload = {
        "name":
            workflow_name,

        "tasks":
            tasks,
    }

    print(
        f"Source:   {source}"
    )

    print(
        f"Duration: {duration:.2f}s"
    )

    print(
        f"Chunks:   {chunk_count}"
    )

    print(
        f"Run:      {run_id}"
    )

    print()

    print(
        "Submitting:",
        workflow_name,
    )

    started_at = (
        time.monotonic()
    )

    result = post_json(
        WORKFLOW_URL,
        payload,
    )

    workflow_id = (
        result["id"]
    )

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
        "Final output:"
    )

    print(
        final_output
    )

    print()

    final = wait_for_terminal(
        workflow_id
    )

    elapsed = (
        time.monotonic()
        - started_at
    )

    print()
    print(
        "Final workflow status:",
        final["status"],
    )

    print(
        f"Wall time: "
        f"{elapsed:.1f}s"
    )

    print_worker_assignments(
        workflow_id
    )

    if (
        final["status"]
        == "SUCCEEDED"
    ):
        print()
        print(
            "Output ready:"
        )

        print(
            final_output
        )
    else:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
