#!/usr/bin/env python3
"""Run a small, repeatable Forge video-throughput benchmark matrix.

The matrix deliberately has exactly three equal chunks so it exercises all
three workers without allowing the workers' advertised capacity to launch a
large, CPU-contending batch at once.
"""

import argparse
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path


WALL_TIME_PATTERN = re.compile(r"^Wall time:\s+([0-9.]+)s$", re.MULTILINE)
THROUGHPUT_PATTERN = re.compile(
    r"^Throughput:\s+([0-9.]+)x realtime$",
    re.MULTILINE,
)


def run_once(script, source, duration, threads):
    command = [
        sys.executable,
        str(script),
        "--source",
        str(source),
        "--limit-seconds",
        str(duration),
        "--chunk-seconds",
        str(duration / 3),
        "--threads-per-task",
        str(threads),
    ]

    print("\n$ " + " ".join(command), flush=True)
    started = time.monotonic()
    completed = subprocess.run(command, text=True, capture_output=True)
    print(completed.stdout, end="")
    print(completed.stderr, end="", file=sys.stderr)

    if completed.returncode:
        raise RuntimeError(
            f"benchmark exited with status {completed.returncode}"
        )

    wall_time = WALL_TIME_PATTERN.search(completed.stdout)
    throughput = THROUGHPUT_PATTERN.search(completed.stdout)

    if wall_time is None or throughput is None:
        raise RuntimeError("could not parse benchmark results")

    return float(wall_time.group(1)), float(throughput.group(1)), (
        time.monotonic() - started
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--duration", type=float, default=120.0)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--threads", type=int, nargs="+", default=[1, 2, 4])
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    script = Path(__file__).with_name("video_benchmark_demo.py")

    if not source.is_file():
        raise SystemExit(f"Source not found: {source}")
    if args.duration <= 0 or args.repetitions < 1:
        raise SystemExit("duration must be > 0 and repetitions must be >= 1")
    if args.duration / 3 <= 0 or any(t < 1 for t in args.threads):
        raise SystemExit("all thread counts must be >= 1")

    print("Forge Video Benchmark Matrix")
    print("=" * 50)
    print(f"Source:       {source.name}")
    print(f"Duration:     {args.duration:.1f}s (three equal chunks)")
    print(f"Repetitions:  {args.repetitions}")
    print(f"Thread cases: {', '.join(map(str, args.threads))}")

    results = []
    for threads in args.threads:
        wall_times = []
        throughputs = []
        for repeat in range(1, args.repetitions + 1):
            print(f"\n--- threads={threads}, run {repeat}/{args.repetitions} ---")
            wall_time, throughput, _ = run_once(
                script, source, args.duration, threads
            )
            wall_times.append(wall_time)
            throughputs.append(throughput)

        results.append(
            (
                threads,
                statistics.median(wall_times),
                statistics.median(throughputs),
                min(wall_times),
                max(wall_times),
            )
        )

    results.sort(key=lambda row: row[1])
    print("\n" + "=" * 50)
    print("Matrix results (sorted by median wall time)")
    print("=" * 50)
    print("threads  median wall  median throughput  wall-time range")
    for threads, wall, throughput, best, worst in results:
        print(
            f"{threads:>7}  {wall:>9.1f}s  {throughput:>16.2f}x  "
            f"{best:.1f}s–{worst:.1f}s"
        )

    winner = results[0]
    print(
        f"\nWinner: --threads-per-task {winner[0]} "
        f"({winner[2]:.2f}x realtime median)"
    )


if __name__ == "__main__":
    main()
