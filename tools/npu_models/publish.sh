#!/bin/bash
# Publishes Gemma NPU model files for chips the apps' Play pack doesn't carry: each goes to the site's models/android/
# folder (thermohammer.gtgroup.dev/models/android/, ThermoHammerWeb/deploy) with its SHA-256 beside it, and manifest.json
# is rebuilt from what's there (manifest.py). Phones with those chips download their file (NpuServerModels.kt) and the
# server requires their NPU part (NpuChipCatalog.cs) within 30 minutes; no app update, no deploy.
#   tools/npu_models/publish.sh out/Gemma3-1B-IT_q4_ekv1280_sm8845.litertlm [more files…]
# Every file must pass the publish gate first (builder/docker/thnpu/verify.py; the CI job runs it). Safe to run again.
set -euo pipefail
[ $# -gt 0 ] || { echo "usage: publish.sh <file.litertlm>…" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
HOST="${TH_DEPLOY_HOST:-myserver}"
BASE="${TH_DEPLOY_DIR:-/home/ubuntu/docker/thweb}"
DIR="$BASE/models/android"
IN="$BASE/.npu-incoming"          # outside what the site serves
ssh "$HOST" "mkdir -p '$DIR' '$IN'"
for f in "$@"; do
    name="$(basename "$f")"
    [[ "$name" =~ ^Gemma3-1B-IT_q[48]_ekv1280_[a-z]+[0-9]+\.litertlm$ ]] || { echo "not a model file name: $name" >&2; exit 1; }
    sha="$(shasum -a 256 "$f" | cut -d' ' -f1)"
    # copy, check, then move into place: a phone never downloads half a file under the published name
    rsync -a --partial --progress "$f" "$HOST:$IN/$name"
    remote="$(ssh "$HOST" "sha256sum '$IN/$name' | cut -d' ' -f1")"
    [ "$sha" = "$remote" ] || { echo "$name: SHA-256 differs on the server" >&2; exit 1; }
    ssh "$HOST" "chmod 644 '$IN/$name' && mv '$IN/$name' '$DIR/$name' && echo '$sha' > '$DIR/$name.sha256'"
    echo "published $name ($sha)"
done
scp -q "$HERE/manifest.py" "$HOST:$IN/manifest.py"
ssh "$HOST" "python3 -I '$IN/manifest.py' '$DIR'"
