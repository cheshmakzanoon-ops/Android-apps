#!/usr/bin/env bash
# Generates the synthetic source clips used by StickerJet tests. Nothing here is a photograph or footage of
# a real person: a coloured disc moves across a flat background. Requires ffmpeg with libx264.
#
# Usage: tools/make_test_media.sh [output_dir]   (default: apps/stickerjet/assets/source)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../assets/source}"
mkdir -p "$out"

# Disc centre moves on a 3-second sine path; radius 36 px; orange disc on navy background.
disc_x='160+80*sin(2*PI*T/3)'
geq_r="if(lt(hypot(X-(${disc_x}),Y-120),36),240,29)"
geq_g="if(lt(hypot(X-(${disc_x}),Y-120),36),130,53)"
geq_b="if(lt(hypot(X-(${disc_x}),Y-120),36),40,87)"
video="color=c=0x1d3557:s=320x240:r=24:d=3,format=rgb24,geq=r='${geq_r}':g='${geq_g}':b='${geq_b}',format=yuv420p"

# 1. Moving subject, 320x240, no rotation metadata.
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$video" \
  -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -movflags +faststart -an \
  "$out/moving_subject_320x240_3s.mp4"

# 2. The same content with a display matrix of 90 degrees clockwise (ffprobe reports rotation=-90), so the
#    displayed frame is 240x320. The `rotate` metadata tag does not produce a display matrix in ffmpeg 6.1,
#    so the clip is encoded to a temporary file and remuxed with -display_rotation (an input option).
#    Exercises the orientation path (MediaMetadataRetriever rotation handling).
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$video" \
  -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -movflags +faststart -an \
  "$work/upright.mp4"
ffmpeg -hide_banner -loglevel error -y -display_rotation:v:0 -90 -i "$work/upright.mp4" \
  -c copy -movflags +faststart \
  "$out/rotated_90_320x240_3s.mp4"

ls -l "$out"
