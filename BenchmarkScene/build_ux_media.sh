#!/bin/bash
# Rebuilds the media of UX Tests 3–5 (ThermoHammerAndroid/app/src/main/assets/media) from the app's own renders.
#
#   1. Render on a device/emulator with the debug + androidTest APKs installed (SceneCaptureTest):
#        photo_lake  — Lake Valley at t = 36 s, 4000×3000, PNG
#        photo_city  — Neon City at t = 33.6 s, 4000×3000, PNG
#        clips       — Neon City, a different three seconds for every clip, so the video test plays one
#                      continuous film: 4K60 (3840×2160, 180 frames) from t = 20 / 23 / 26 / 29 s for H.264 /
#                      HEVC / VP9 / AV1, 8K30 (7680×4320, 90 frames) from t = 32 / 35 s for HEVC / AV1 (JPEG q93)
#   2. Encode here: WebP q88 + AVIF q62 photos (Pillow ≥ 11 with AVIF), and the clips at streaming-like bitrates —
#      4K60: H.264 High 30 Mb/s, HEVC Main 10 18 Mb/s, VP9 16 Mb/s (2-pass), AV1 10-bit 10 Mb/s (SVT-AV1);
#      8K30 (level 6.0 — what an 8K30-only decoder still takes): HEVC Main 10 40 Mb/s, AV1 10-bit 24 Mb/s —
#      a keyframe every second, TV range.
#
#   usage: build_ux_media.sh <work dir> <python with Pillow> [clips]     ("clips": only the video clips)
set -euo pipefail
WORK="${1:?work dir}"; PY="${2:-python3}"; ONLY="${3:-}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ASSETS="$(cd "$(dirname "$0")/.." && pwd)/ThermoHammerAndroid/app/src/main/assets/media"
FILES=/sdcard/Android/data/com.granturismo.thermohammer/files
RUN=(shell am instrument -w -e class com.example.thermohammer.engine.gpu.SceneCaptureTest)
RUNNER=com.granturismo.thermohammer.test/androidx.test.runner.AndroidJUnitRunner
L=(-hide_banner -loglevel error -y)
VF="vflip,scale=in_range=pc:out_range=tv"
mkdir -p "$ASSETS"

if [ "$ONLY" != clips ]; then
"$ADB" "${RUN[@]}" -e scene lake -e frames 1 -e start 36 -e w 4000 -e h 3000 -e png true "$RUNNER"
"$ADB" pull "$FILES/capture_lake/f_0000.png" "$WORK/lake.png"
"$ADB" "${RUN[@]}" -e scene neon -e frames 1 -e start 33.6 -e w 4000 -e h 3000 -e png true "$RUNNER"
"$ADB" pull "$FILES/capture_neon/f_0000.png" "$WORK/city.png"
"$PY" - "$WORK" "$ASSETS" <<'PY'
import sys
from PIL import Image
work, assets = sys.argv[1], sys.argv[2]
for name in ("lake", "city"):
    im = Image.open(f"{work}/{name}.png").convert("RGB").transpose(Image.FLIP_TOP_BOTTOM)   # GL rows are bottom-up
    im.save(f"{assets}/photo_{name}.webp", quality=88, method=4)
    im.save(f"{assets}/photo_{name}.avif", quality=62)
PY
fi

# render <name> <start s> <fps> <frames> <w> <h>: Neon City frames into $WORK/<name>/
render() {
  rm -rf "$WORK/$1"; mkdir -p "$WORK/$1"
  "$ADB" "${RUN[@]}" -e scene neon -e frames "$4" -e fps "$3" -e start "$2" -e w "$5" -e h "$6" "$RUNNER"
  "$ADB" pull "$FILES/capture_neon/." "$WORK/$1/" >/dev/null
  "$ADB" shell rm -rf "$FILES/capture_neon"
}
render avc 20 60 180 3840 2160
render hevc 23 60 180 3840 2160
render vp9 26 60 180 3840 2160
render av1 29 60 180 3840 2160
render hevc8k 32 30 90 7680 4320
render av18k 35 30 90 7680 4320

in4k() { echo -framerate 60 -i "$WORK/$1/f_%04d.jpg" -frames:v 180; }
in8k() { echo -framerate 30 -i "$WORK/$1/f_%04d.jpg" -frames:v 90; }
ffmpeg "${L[@]}" $(in4k avc) -vf "$VF,format=yuv420p" -c:v libx264 -profile:v high -level 5.2 -preset slow \
  -b:v 30M -maxrate 36M -bufsize 60M -g 60 -color_range tv -movflags +faststart "$ASSETS/clip_avc.mp4"
ffmpeg "${L[@]}" $(in4k hevc) -vf "$VF,format=yuv420p10le" -c:v libx265 -profile:v main10 -preset slow -b:v 18M \
  -x265-params "vbv-maxrate=22000:vbv-bufsize=40000:keyint=60:log-level=error" -tag:v hvc1 -color_range tv \
  -movflags +faststart "$ASSETS/clip_hevc10.mp4"
ffmpeg "${L[@]}" $(in4k vp9) -vf "$VF,format=yuv420p" -c:v libvpx-vp9 -b:v 16M -row-mt 1 -deadline good -cpu-used 3 \
  -g 60 -pass 1 -passlogfile "$WORK/vp9" -f null /dev/null
ffmpeg "${L[@]}" $(in4k vp9) -vf "$VF,format=yuv420p" -c:v libvpx-vp9 -b:v 16M -row-mt 1 -deadline good -cpu-used 3 \
  -g 60 -pass 2 -passlogfile "$WORK/vp9" -color_range tv "$ASSETS/clip_vp9.webm"
ffmpeg "${L[@]}" $(in4k av1) -vf "$VF,format=yuv420p10le" -c:v libsvtav1 -preset 6 -b:v 10M -g 60 \
  -svtav1-params "log-level=0" -color_range tv -movflags +faststart "$ASSETS/clip_av1.mp4"
ffmpeg "${L[@]}" $(in8k hevc8k) -vf "$VF,format=yuv420p10le" -c:v libx265 -profile:v main10 -preset medium -b:v 40M \
  -x265-params "level-idc=60:vbv-maxrate=48000:vbv-bufsize=80000:keyint=30:log-level=error" -tag:v hvc1 \
  -color_range tv -movflags +faststart "$ASSETS/clip8k_hevc10.mp4"
ffmpeg "${L[@]}" $(in8k av18k) -vf "$VF,format=yuv420p10le" -c:v libsvtav1 -preset 7 -b:v 24M -g 30 \
  -svtav1-params "log-level=0" -color_range tv -movflags +faststart "$ASSETS/clip8k_av1.mp4"
ls -la "$ASSETS"
