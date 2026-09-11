"""Bounded FFmpeg stages launched by the dashboard video template."""
import json
from pathlib import Path
import re
import subprocess
import sys


def run(*args):
    subprocess.run(args, check=True)


def probe(file):
    return json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(file)
    ]))


def main():
    run_id, stage = sys.argv[1:]
    if not re.fullmatch(r"[a-f0-9]{8}", run_id):
        raise ValueError("Invalid run ID")
    root = Path(__file__).resolve().parent.parent / "demo/video"
    source = root / "input/tears-of-steel-4k.webm"
    output = root / "dashboard-runs" / run_id
    if stage == "inspect-video":
        info = probe(source)
        if float(info["format"]["duration"]) < 30:
            raise ValueError("The demo needs a source of at least 30 seconds")
        output.mkdir(parents=True, exist_ok=True)
        print(json.dumps(info, indent=2), flush=True)
    elif re.fullmatch(r"transcode-[0-5]", stage):
        index = int(stage[-1])
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-ss", str(index * 5), "-i", str(source), "-t", "5",
            "-map", "0:v:0", "-an", "-vf", "eq=contrast=1.03:saturation=1.05",
            "-c:v", "libx264", "-preset", "fast", "-crf", "21", "-pix_fmt", "yuv420p",
            "-threads", "2", str(output / f"chunk-{index}.mp4"))
    elif stage == "merge-video":
        manifest = output / "concat.txt"
        manifest.write_text("".join(f"file 'chunk-{i}.mp4'\n" for i in range(6)))
        temporary = output / "merging.mp4"
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "concat",
            "-safe", "0", "-i", str(manifest), "-i", str(source),
            "-map", "0:v:0", "-map", "1:a:0?", "-c:v", "copy", "-c:a", "aac",
            "-t", "30", "-movflags", "+faststart", str(temporary))
        info = probe(temporary)
        video = next(s for s in info["streams"] if s["codec_type"] == "video")
        if not 29.5 <= float(info["format"]["duration"]) <= 30.5 or (video["width"], video["height"]) != (3840, 1714):
            raise ValueError("Output failed duration/resolution validation")
        run("ffmpeg", "-v", "error", "-xerror", "-i", str(temporary), "-f", "null", "-")
        temporary.replace(output / "forge-output.mp4")
        print(json.dumps(info, indent=2), flush=True)
    else:
        raise ValueError("Unknown video stage")
    print(f"{stage}: complete", flush=True)


if __name__ == "__main__":
    main()
