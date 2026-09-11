#!/usr/bin/env python3
"""Compare Forge workflow latency with an equivalent local FFmpeg pipeline."""

import argparse
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path


WORKFLOW_ID = re.compile(r"^Workflow ID:\s+([\w-]+)$", re.MULTILINE)
CONTROLLER_URL = "http://127.0.0.1:8080"


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)


def direct_pipeline(source, run_root, duration, threads):
    """Match the benchmark demo's probe, three transcodes, and concat."""
    processed = run_root / "processed"
    output = run_root / "output"
    processed.mkdir(parents=True)
    output.mkdir()
    chunk_duration = duration / 3

    started = time.monotonic()
    run([
        "ffprobe", "-v", "error", "-show_entries", "format=duration,size,bit_rate",
        "-show_entries", "stream=codec_name,codec_type,width,height",
        "-of", "default=noprint_wrappers=1", str(source),
    ], stdout=subprocess.DEVNULL)

    processes = []
    for index in range(3):
        output_file = processed / f"chunk_{index:03d}.mp4"
        processes.append(subprocess.Popen([
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "warning",
            "-threads", str(threads), "-ss", f"{index * chunk_duration:.6f}",
            "-i", str(source), "-t", f"{chunk_duration:.6f}",
            "-filter_threads", str(threads),
            "-vf", "scale=1920:-2,eq=contrast=1.03:saturation=1.05",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-threads", str(threads), "-c:a", "aac", "-b:a", "128k",
            "-avoid_negative_ts", "make_zero", str(output_file),
        ]))

    for process in processes:
        if process.wait() != 0:
            raise RuntimeError("direct FFmpeg chunk failed")

    manifest = processed / "concat.txt"
    manifest.write_text(
        "".join(f"file '{file.name}'\n" for file in sorted(processed.glob("chunk_*.mp4")))
    )
    run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "warning", "-f", "concat",
        "-safe", "0", "-i", str(manifest), "-c", "copy", "-movflags",
        "+faststart", str(output / "benchmark.mp4"),
    ], stdout=subprocess.DEVNULL)
    return time.monotonic() - started


def forge_pipeline(source, duration, threads):
    demo = Path(__file__).with_name("video_benchmark_demo.py")
    completed = run([
        sys.executable, str(demo), "--source", str(source), "--limit-seconds",
        str(duration), "--chunk-seconds", str(duration / 3), "--threads-per-task",
        str(threads),
    ], capture_output=True)
    print(completed.stdout, end="")
    workflow = WORKFLOW_ID.search(completed.stdout)
    if workflow is None:
        raise RuntimeError("could not find Forge workflow ID")

    with urllib.request.urlopen(
        f"{CONTROLLER_URL}/api/workflows/{workflow.group(1)}/events", timeout=15
    ) as response:
        events = json.load(response)

    timestamps = [
        event["createdAt"] for event in events
        if event["type"] in {"WORKFLOW_CREATED", "TASK_SUCCEEDED"}
    ]
    from datetime import datetime
    start = min(datetime.fromisoformat(value.replace("Z", "+00:00")) for value in timestamps)
    end = max(datetime.fromisoformat(value.replace("Z", "+00:00")) for value in timestamps)
    return (end - start).total_seconds()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--duration", type=float, default=120)
    parser.add_argument("--threads-per-task", type=int, default=2)
    parser.add_argument("--repetitions", type=int, default=3)
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    if not source.is_file():
        raise SystemExit(f"Source not found: {source}")
    if args.duration <= 0 or args.threads_per_task < 1 or args.repetitions < 1:
        raise SystemExit("duration, threads, and repetitions must all be positive")

    root = Path(__file__).resolve().parents[1] / "demo/video/benchmark-runs"
    pairs = []
    print("Forge vs direct FFmpeg")
    print("=" * 50)
    print(f"Duration: {args.duration:.1f}s; chunks: 3; threads/task: {args.threads_per_task}")

    for repeat in range(args.repetitions):
        # Alternate order to avoid always giving one case warmer CPU/file caches.
        cases = ["direct", "forge"] if repeat % 2 == 0 else ["forge", "direct"]
        measurements = {}
        for case in cases:
            print(f"\n--- run {repeat + 1}/{args.repetitions}: {case} ---")
            if case == "direct":
                measurements[case] = direct_pipeline(
                    source, root / f"direct-{uuid.uuid4().hex[:8]}",
                    args.duration, args.threads_per_task,
                )
            else:
                measurements[case] = forge_pipeline(
                    source, args.duration, args.threads_per_task,
                )
            print(f"{case}: {measurements[case]:.3f}s")
        pairs.append((measurements["direct"], measurements["forge"]))

    direct = [pair[0] for pair in pairs]
    forge = [pair[1] for pair in pairs]
    overhead = [f - d for d, f in pairs]
    print("\n" + "=" * 50)
    print("Results")
    print(f"Direct median: {statistics.median(direct):.3f}s")
    print(f"Forge median:  {statistics.median(forge):.3f}s")
    print(f"Forge overhead: {statistics.median(overhead):.3f}s "
          f"({statistics.median(overhead) / statistics.median(direct) * 100:.1f}% of direct)")


if __name__ == "__main__":
    main()
