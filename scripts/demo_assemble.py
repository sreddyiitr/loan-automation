"""make demo, step 2 (scripts/demo.sh): turns the recording into the demo files.

  demo.mp4   1280x720 at 15 fps, H.264; with the voice clips laid at each scene's start (AAC mono)
  demo.webp  a silent loop for the README, 1000 px wide at 8 fps
  demo.srt   the spoken words, timed to the scenes (LinkedIn's caption upload)

Usage: python3 scripts/demo_assemble.py <outDir> [voiceDir]   (Pillow is needed for the WebP;
scripts/demo.sh runs it with `uv run --with pillow`). ffmpeg must be on the PATH.
"""
from __future__ import annotations

import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

NARRATION = Path(__file__).resolve().parents[1] / "dashboard/web/demo/narration.json"
VOICE_DELAY = 0.25          # seconds after a scene starts before its line is spoken


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def duration(path: str) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         check=True, capture_output=True, text=True).stdout
    return float(out.strip())


def mp4(out: Path, video: str, scenes: list[dict], voice: Path | None) -> None:
    clips = [voice / f"line-{s['line']}.wav" for s in scenes] if voice else []
    common = ["-r", "15", "-c:v", "libx264", "-preset", "slow", "-crf", "30", "-tune", "stillimage",
              "-pix_fmt", "yuv420p", "-movflags", "+faststart"]
    if not clips or not all(c.exists() for c in clips):
        ffmpeg("-i", video, *common, "-an", str(out / "demo.mp4"))
        return
    inputs = [arg for c in clips for arg in ("-i", str(c))]
    delays = ";".join(f"[{i}:a]adelay={int((s['at'] + VOICE_DELAY) * 1000)}[a{i}]" for i, s in enumerate(scenes, 1))
    mix = "".join(f"[a{i}]" for i in range(1, len(scenes) + 1))
    graph = f"{delays};{mix}amix=inputs={len(scenes)}:normalize=0:duration=longest,apad[aout]"
    ffmpeg("-i", video, *inputs, "-filter_complex", graph, "-map", "0:v", "-map", "[aout]", *common,
           "-c:a", "aac", "-b:a", "64k", "-ac", "1", "-shortest", str(out / "demo.mp4"))


def webp(out: Path, video: str) -> None:
    from PIL import Image                                  # only this step needs Pillow
    frames_dir = tempfile.mkdtemp(prefix="demo-frames-")
    try:
        ffmpeg("-i", video, "-vf", "fps=8,scale=1000:-2:flags=lanczos", f"{frames_dir}/f%04d.png")
        frames = [Image.open(f).convert("RGB") for f in sorted(glob.glob(f"{frames_dir}/*.png"))]
        frames[0].save(out / "demo.webp", save_all=True, append_images=frames[1:], duration=125, loop=0,
                       quality=60, method=6, minimize_size=True, allow_mixed=True)
    finally:
        shutil.rmtree(frames_dir, ignore_errors=True)


def srt(out: Path, scenes: list[dict], durations: dict[int, float]) -> None:
    spoken = {l["n"]: l["spoken"] for l in json.loads(NARRATION.read_text())["lines"]}

    def ts(t: float) -> str:
        ms = int(round(t * 1000))
        return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"
    with open(out / "demo.srt", "w") as f:
        for i, s in enumerate(scenes, 1):
            a = s["at"] + VOICE_DELAY
            f.write(f"{i}\n{ts(a)} --> {ts(a + durations[s['line']] + 0.2)}\n{spoken[s['line']]}\n\n")


def main() -> None:
    out = Path(sys.argv[1])
    voice = Path(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2] else None
    video = sorted(glob.glob(str(out / "raw" / "*.webm")))[-1]
    scenes = json.loads((out / "timeline.json").read_text())["scenes"]
    durations = {int(n): float(d) for n, d in (l.split() for l in (out / "durations.txt").read_text().split("\n") if l)}
    mp4(out, video, scenes, voice)
    webp(out, video)
    srt(out, scenes, durations)
    for name in ("demo.mp4", "demo.webp", "demo.srt"):
        p = out / name
        print(f"  {p}  {os.path.getsize(p) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
