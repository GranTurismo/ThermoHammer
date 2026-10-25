#!/bin/bash
# Rebuilds the media of UX Tests 3–5 (ThermoHammerAndroid/app/src/main/assets/media) from the app's own renders.
#
#   1. Render on a device/emulator with the debug + androidTest APKs installed (SceneCaptureTest):
#        photo_lake  — Lake Valley at t = 36 s, 4000×3000, PNG
#        photo_city  — Neon City at t = 33.6 s, 4000×3000, PNG
#        clip        — Neon City from t = 20 s, 3840×2160, 60 fps, 60 frames (JPEG q93)
#   2. Encode here: WebP q88 + AVIF q62 photos (Pillow ≥ 11 with AVIF), and one second of 4K60 per codec at
#      streaming-like bitrates — H.264 High 30 Mb/s, HEVC Main 10 18 Mb/s, VP9 16 Mb/s (2-pass), AV1 10-bit
#      10 Mb/s (SVT-AV1) — keyframe at the start, TV range.
#
#   usage: build_ux_media.sh <work dir> <python with Pillow>
set -euo pipefail
WORK="${1:?work dir}"; PY="${2:-python3}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ASSETS="$(cd "$(dirname "$0")/.." && pwd)/ThermoHammerAndroid/app/src/main/assets/media"
FILES=/sdcard/Android/data/com.granturismo.thermohammer/files
RUN=(shell am instrument -w -e class com.example.thermohammer.engine.gpu.SceneCaptureTest)
RUNNER=com.granturismo.thermohammer.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$WORK/clip" "$ASSETS"

"$ADB" "${RUN[@]}" -e scene lake -e frames 1 -e start 36 -e w 4000 -e h 3000 -e png true "$RUNNER"
"$ADB" pull "$FILES/capture_lake/f_0000.png" "$WORK/lake.png"
"$ADB" "${RUN[@]}" -e scene neon -e frames 1 -e start 33.6 -e w 4000 -e h 3000 -e png true "$RUNNER"
"$ADB" pull "$FILES/capture_neon/f_0000.png" "$WORK/city.png"
"$ADB" "${RUN[@]}" -e scene neon -e frames 60 -e fps 60 -e start 20 -e w 3840 -e h 2160 "$RUNNER"
"$ADB" pull "$FILES/capture_neon/." "$WORK/clip/"

"$PY" - "$WORK" "$ASSETS" <<'PY'
import sys
from PIL import Image
work, assets = sys.argv[1], sys.argv[2]
for name in ("lake", "city"):
    im = Image.open(f"{work}/{name}.png").convert("RGB").transpose(Image.FLIP_TOP_BOTTOM)   # GL rows are bottom-up
    im.save(f"{assets}/photo_{name}.webp", quality=88, method=4)
    im.save(f"{assets}/photo_{name}.avif", quality=62)
PY

cd "$WORK/clip"
VF="vflip,scale=in_range=pc:out_range=tv"
IN=(-framerate 60 -i f_%04d.jpg -frames:v 60)
L=(-hide_banner -loglevel error -y)
ffmpeg "${L[@]}" "${IN[@]}" -vf "$VF,format=yuv420p" -c:v libx264 -profile:v high -level 5.2 -preset slow \
  -b:v 30M -maxrate 36M -bufsize 60M -g 60 -color_range tv -movflags +faststart "$ASSETS/clip_avc.mp4"
ffmpeg "${L[@]}" "${IN[@]}" -vf "$VF,format=yuv420p10le" -c:v libx265 -profile:v main10 -preset slow -b:v 18M \
  -x265-params "vbv-maxrate=22000:vbv-bufsize=40000:keyint=60:log-level=error" -tag:v hvc1 -color_range tv \
  -movflags +faststart "$ASSETS/clip_hevc10.mp4"
ffmpeg "${L[@]}" "${IN[@]}" -vf "$VF,format=yuv420p" -c:v libvpx-vp9 -b:v 16M -row-mt 1 -deadline good -cpu-used 3 \
  -g 60 -pass 1 -passlogfile "$WORK/vp9" -f null /dev/null
ffmpeg "${L[@]}" "${IN[@]}" -vf "$VF,format=yuv420p" -c:v libvpx-vp9 -b:v 16M -row-mt 1 -deadline good -cpu-used 3 \
  -g 60 -pass 2 -passlogfile "$WORK/vp9" -color_range tv "$ASSETS/clip_vp9.webm"
ffmpeg "${L[@]}" "${IN[@]}" -vf "$VF,format=yuv420p10le" -c:v libsvtav1 -preset 6 -b:v 10M -g 60 \
  -svtav1-params "log-level=0" -color_range tv -movflags +faststart "$ASSETS/clip_av1.mp4"
ls -la "$ASSETS"
