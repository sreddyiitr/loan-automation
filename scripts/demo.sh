#!/usr/bin/env bash
# make demo: records the funder-dashboard demo and writes target/demo/{demo.mp4,demo.webp,demo.srt}.
#   1. reads each scene's length: a voice clip DEMO_VOICE_DIR/line-<n>.wav sets it, otherwise
#      dashboard/web/demo/narration.json's seconds;
#   2. warms the local model, so Summarize answers in a second or two;
#   3. records the dashboard with Playwright (dashboard/web/demo/record.mjs);
#   4. lays the voice clips at each scene's start and encodes the files (scripts/demo_assemble.py).
# Needs `make dashboard` running, ffmpeg (brew install ffmpeg) and uv. Without DEMO_VOICE_DIR the MP4
# is silent; the captions are on screen either way. Voice clips stay wherever you keep them: the
# repo never holds them. Plain strings, no arrays (CLAUDE.md).
set -eu
cd "$(dirname "$0")/.." || exit 1
OUT="${DEMO_OUT:-target/demo}"
VOICE="${DEMO_VOICE_DIR:-}"

command -v ffmpeg >/dev/null 2>&1 || { echo "demo: ffmpeg is missing: brew install ffmpeg"; exit 1; }
command -v uv >/dev/null 2>&1 || { echo "demo: uv is missing: brew install uv"; exit 1; }
curl -sf -m 3 "${DEMO_URL:-http://localhost:5173}/agent/.well-known/agent-card.json" >/dev/null \
  || { echo "demo: the dashboard isn't answering; start it with make dashboard"; exit 1; }
if [ -n "$VOICE" ] && [ ! -f "$VOICE/line-1.wav" ]; then
  echo "demo: DEMO_VOICE_DIR=$VOICE has no line-1.wav (expects line-1.wav … line-9.wav)"; exit 1
fi

rm -rf "$OUT/raw"
mkdir -p "$OUT"
: > "$OUT/durations.txt"
for n in $(python3 -c "import json; print(' '.join(str(l['n']) for l in json.load(open('dashboard/web/demo/narration.json'))['lines']))"); do
  if [ -n "$VOICE" ] && [ -f "$VOICE/line-$n.wav" ]; then
    d="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$VOICE/line-$n.wav")"
  else
    d="$(python3 -c "import json,sys; print(next(l['seconds'] for l in json.load(open('dashboard/web/demo/narration.json'))['lines'] if l['n']==$n))")"
  fi
  echo "$n $d" >> "$OUT/durations.txt"
done

# warm the model (optional: without Ollama, Summarize shows "assistant unavailable" on camera)
curl -s -m 60 localhost:11434/api/generate \
  -d '{"model":"gemma4:e4b","prompt":"hi","stream":false,"think":false,"options":{"num_predict":1}}' >/dev/null 2>&1 \
  || echo "demo: Ollama isn't answering; Summarize will show the assistant as unavailable"

echo "demo: recording (about 70 s)"
node dashboard/web/demo/record.mjs "$OUT" >/dev/null
echo "demo: encoding"
uv run --quiet --with pillow python scripts/demo_assemble.py "$OUT" "$VOICE"
