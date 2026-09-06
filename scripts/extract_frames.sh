#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/extract_frames.sh [fps] [video ...]
# Example: ./scripts/extract_frames.sh 2 video_20260816_162107.mp4
FPS="${1:-2}"
if [[ $# -gt 0 ]]; then shift; fi
VIDEOS=( "$@" )
if [[ ${#VIDEOS[@]} -eq 0 ]]; then VIDEOS=(video_*.mp4); fi

for video in "${VIDEOS[@]}"; do
  [[ -f "$video" ]] || { echo "找不到视频: $video" >&2; continue; }
  name="$(basename "${video%.*}")"
  out="frames/$name"
  mkdir -p "$out"
  ffmpeg -hide_banner -loglevel error -y -i "$video" -vf "fps=$FPS" -q:v 2 "$out/frame_%06d.jpg"
  count=$(find "$out" -maxdepth 1 -name 'frame_*.jpg' | wc -l)
  echo "$video -> $out ($count 帧, ${FPS} fps)"
done
