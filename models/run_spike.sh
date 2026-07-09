#!/usr/bin/env bash
# Run the ALPR spike over a set of footage clips and save logs for later analysis.
# Usage:        ./run_spike.sh
#   higher fps: FPS=12 ./run_spike.sh
#   + annotated video (large; disk is tight): ANNOTATE=1 ./run_spike.sh
set -uo pipefail

MODELS_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$MODELS_DIR" || { echo "cannot cd to $MODELS_DIR"; exit 1; }

PY=".venv-convert/bin/python"
FPS="${FPS:-8}"            # mirror the on-device 8 fps throttle
ANNOTATE="${ANNOTATE:-0}"  # 1 = also write annotated videos into footage/annotated

STAMP="$(date +%Y%m%d_%H%M%S)"
LOG_DIR="footage/spike-logs/$STAMP"
mkdir -p "$LOG_DIR"
COMBINED="$LOG_DIR/_combined.log"

CLIPS=(footage/*.mp4)
if [ ! -e "${CLIPS[0]}" ]; then
  echo "no footage .mp4 files found in $MODELS_DIR/footage/"; exit 1
fi

ANNO_ARGS=()
if [ "$ANNOTATE" = "1" ]; then
  mkdir -p footage/annotated
  ANNO_ARGS=(--annotated footage/annotated)
fi

{
  echo "Spike run $STAMP — ${#CLIPS[@]} clip(s), fps=$FPS, annotate=$ANNOTATE"
  echo "logs -> $LOG_DIR"
} | tee "$COMBINED"

for clip in "${CLIPS[@]}"; do
  name="$(basename "$clip" .mp4)"
  log="$LOG_DIR/$name.log"
  { echo; echo "===== $name ====="; } | tee -a "$COMBINED"
  stdbuf -oL -eL "$PY" spike_video_test.py \
      --video "$clip" --fps "$FPS" "${ANNO_ARGS[@]}" \
      2>&1 | tee "$log" | tee -a "$COMBINED"
done

{ echo; echo "Done. Per-clip logs + _combined.log in: $LOG_DIR"; } | tee -a "$COMBINED"
