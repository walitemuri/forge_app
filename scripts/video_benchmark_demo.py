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
from collections import Counter
from pathlib import Path


CONTROLLER_URL = os.environ.get(
    "FORGE_CONTROLLER_URL",
    "http://127.0.0.1:8080",
)

WORKFLOW_URL = f"{CONTROLLER_URL}/api/workflows"
WORKER_URL = f"{CONTROLLER_URL}/api/workers"


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

    with urllib.request.urlopen(
        request,
        timeout=15,
    ) as response:
        text = response.read().decode("utf-8")

        if not text:
            return None

        return json.loads(text)


def get_json(url):
    return request_json("GET", url)


def post_json(url, body):
    return request_json(
        "POST",
        url,
        body,
    )


def quote(value):
    return shlex.quote(str(value))


def probe_duration(source):
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

    return float(output.strip())


def get_online_workers():
    workers = get_json(WORKER_URL)

    return [
        worker
        for worker in workers
        if (
            worker.get("online")
            and
            worker.get("commandStreamConnected")
        )
    ]


def shell_task(
    key,
    command,
    dependencies,
    *,
    timeout_seconds=900,
    max_attempts=2,
):
    return {
        "key": key,
        "command": "bash",
        "arguments": [
            "-lc",
            command,
        ],
        "dependsOn": dependencies,
        "maxAttempts": max_attempts,
        "timeoutSeconds": timeout_seconds,
    }


def wait_for_terminal(
    workflow_id,
    timeout=3600,
):
    deadline = time.monotonic() + timeout

    terminal = {
        "SUCCEEDED",
        "FAILED",
        "CANCELLED",
    }

    while time.monotonic() < deadline:
        workflow = get_json(
            f"{WORKFLOW_URL}/{workflow_id}"
        )

        status = workflow["status"]

        tasks = workflow.get("tasks", [])

        counts = Counter(
            task["status"]
            for task in tasks
        )

        summary = " ".join(
            f"{key}={value}"
            for key, value
            in sorted(counts.items())
        )

        print(
            f"\r{status:<10} {summary:<70}",
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


def get_assignments(workflow_id):
    workflow = get_json(
        f"{WORKFLOW_URL}/{workflow_id}"
    )

    events = get_json(
        f"{WORKFLOW_URL}/{workflow_id}/events"
    )

    task_keys = {}

    for task in workflow["tasks"]:
        task_id = (
            task.get("taskId")
            or task.get("id")
        )

        if task_id:
            task_keys[task_id] = task["key"]

    assignments = []

    for event in events:
        if (
            event.get("type")
            == "TASK_DISPATCHED"
            and event.get("workerId")
        ):
            key = task_keys.get(
                event.get("taskId"),
                "unknown",
            )

            assignments.append(
                (
                    key,
                    event["workerId"],
                )
            )

    return assignments


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--source",
        required=True,
    )

    parser.add_argument(
        "--limit-seconds",
        type=float,
        default=180.0,
    )

    parser.add_argument(
        "--chunk-seconds",
        type=float,
        default=10.0,
    )

    parser.add_argument(
        "--threads-per-task",
        type=int,
        default=2,
    )

    args = parser.parse_args()

    source = (
        Path(args.source)
        .expanduser()
        .resolve()
    )

    if not source.is_file():
        raise SystemExit(
            f"Source not found: {source}"
        )

    if args.limit_seconds <= 0:
        raise SystemExit(
            "--limit-seconds must be > 0"
        )

    if args.chunk_seconds <= 0:
        raise SystemExit(
            "--chunk-seconds must be > 0"
        )

    if args.threads_per_task < 1:
        raise SystemExit(
            "--threads-per-task must be >= 1"
        )

    workers = get_online_workers()

    if len(workers) < 3:
        raise SystemExit(
            "This benchmark requires at least "
            f"3 online workers; found {len(workers)}"
        )

    source_duration = probe_duration(source)

    benchmark_duration = min(
        source_duration,
        args.limit_seconds,
    )

    chunk_count = math.ceil(
        benchmark_duration
        / args.chunk_seconds
    )

    run_id = uuid.uuid4().hex[:8]

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
        / "benchmark-runs"
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
    processed_q = quote(processed_dir)

    tasks = []

    tasks.append(
        shell_task(
            "inspect-video",
            f"""
set -euo pipefail

echo "=== Forge Video Benchmark ==="

ffprobe \
  -v error \
  -show_entries \
  format=duration,size,bit_rate \
  -show_entries \
  stream=codec_name,codec_type,width,height \
  -of default=noprint_wrappers=1 \
  {source_q}
""",
            [],
            timeout_seconds=60,
            max_attempts=1,
        )
    )

    transcode_keys = []

    for index in range(chunk_count):
        start = (
            index
            * args.chunk_seconds
        )

        segment_duration = min(
            args.chunk_seconds,
            benchmark_duration - start,
        )

        key = f"transcode-{index:03d}"

        transcode_keys.append(key)

        output_file = (
            processed_dir
            / f"chunk_{index:03d}.mp4"
        )

        command = f"""
set -euo pipefail

echo "Forge benchmark chunk {index:03d}"
echo "start={start:.3f}"
echo "duration={segment_duration:.3f}"

ffmpeg \
  -y \
  -hide_banner \
  -loglevel warning \
  -threads {args.threads_per_task} \
  -ss {start:.6f} \
  -i {source_q} \
  -t {segment_duration:.6f} \
  -filter_threads {args.threads_per_task} \
  -vf "scale=1920:-2,eq=contrast=1.03:saturation=1.05" \
  -c:v libx264 \
  -preset veryfast \
  -crf 22 \
  -threads {args.threads_per_task} \
  -c:a aac \
  -b:a 128k \
  -avoid_negative_ts make_zero \
  {quote(output_file)}

test -s {quote(output_file)}

echo "chunk {index:03d} complete"
"""

        tasks.append(
            shell_task(
                key,
                command,
                ["inspect-video"],
                timeout_seconds=1200,
                max_attempts=2,
            )
        )

    final_output = (
        output_dir
        / "forge-benchmark.mp4"
    )

    merge_command = f"""
set -euo pipefail

cd {processed_q}

rm -f concat.txt

for file in chunk_*.mp4; do
    printf "file '%s'\\n" "$file"
done > concat.txt

echo "=== concat manifest ==="
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
  {quote(final_output)}

test -s {quote(final_output)}

echo
echo "=== final output ==="

ffprobe \
  -v error \
  -show_entries \
  format=duration,size,bit_rate \
  -show_entries \
  stream=codec_name,width,height \
  -of default=noprint_wrappers=1 \
  {quote(final_output)}
"""

    tasks.append(
        shell_task(
            "merge-video",
            merge_command,
            transcode_keys,
            timeout_seconds=180,
            max_attempts=1,
        )
    )

    workflow_name = (
        f"4k-video-benchmark-{run_id}"
    )

    payload = {
        "name": workflow_name,
        "tasks": tasks,
    }

    print()
    print("Forge Video Benchmark")
    print("=" * 50)
    print(f"Source:           {source.name}")
    print(
        f"Source duration:  "
        f"{source_duration:.1f}s"
    )
    print(
        f"Benchmark range:  "
        f"{benchmark_duration:.1f}s"
    )
    print(
        f"Chunk size:       "
        f"{args.chunk_seconds:.1f}s"
    )
    print(f"Chunks:           {chunk_count}")
    print(f"Workers online:   {len(workers)}")
    print(
        f"Threads/task:     "
        f"{args.threads_per_task}"
    )
    print("Codec:            H.264 / libx264")
    print("Resolution:       1920px output")
    print()

    for worker in workers:
        print(
            f"  {worker['id']:<20} "
            f"capacity={worker.get('capacity')}"
        )

    print()
    print("Submitting workflow...")

    started = time.monotonic()

    result = post_json(
        WORKFLOW_URL,
        payload,
    )

    workflow_id = result["id"]

    print(
        f"Workflow ID: {workflow_id}"
    )

    print(
        "Dashboard:   "
        "http://localhost:3000"
        f"/workflows/{workflow_id}"
    )

    print()

    final = wait_for_terminal(
        workflow_id
    )

    wall_time = (
        time.monotonic()
        - started
    )

    assignments = get_assignments(
        workflow_id
    )

    chunk_assignments = [
        worker
        for key, worker
        in assignments
        if key.startswith("transcode-")
    ]

    distribution = Counter(
        chunk_assignments
    )

    print()
    print("=" * 50)
    print("Forge Video Benchmark Results")
    print("=" * 50)
    print(
        f"Status:           "
        f"{final['status']}"
    )
    print(
        f"Video processed:  "
        f"{benchmark_duration:.1f}s"
    )
    print(
        f"Wall time:        "
        f"{wall_time:.1f}s"
    )

    if wall_time > 0:
        throughput = (
            benchmark_duration
            / wall_time
        )

        print(
            f"Throughput:       "
            f"{throughput:.2f}x realtime"
        )

    print(
        f"Chunks:           "
        f"{chunk_count}"
    )

    print()
    print("Worker distribution:")

    for worker_id, count in sorted(
        distribution.items()
    ):
        print(
            f"  {worker_id:<20} "
            f"{count} chunks"
        )

    print()
    print("Dispatch history:")

    for key, worker_id in assignments:
        print(
            f"  {key:<20} -> "
            f"{worker_id}"
        )

    print()
    print(
        f"Output: {final_output}"
    )

    if final["status"] != "SUCCEEDED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
