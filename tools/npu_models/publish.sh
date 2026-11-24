#!/bin/bash
# Publishes Gemma NPU model files for chips the apps' Play pack doesn't carry: each goes to the site's models/android/
# folder (thermohammer.gtgroup.dev/models/android/, ThermoHammerWeb/deploy) with its SHA-256 beside it, and manifest.json
# is rebuilt from what's there. Phones with those chips download their file (NpuServerModels.kt) and the server requires
# their NPU part (NpuChipCatalog.cs) within 30 minutes; no app update, no deploy.
#   tools/npu_models/publish.sh Gemma3-1B-IT_q4_ekv1280_sm8635.litertlm [more files…]
# A file's chip is the last "_<soc>" of its name. Safe to run again.
set -euo pipefail
[ $# -gt 0 ] || { echo "usage: publish.sh <file.litertlm>…" >&2; exit 2; }
HOST="${TH_DEPLOY_HOST:-myserver}"
DIR="${TH_DEPLOY_DIR:-/home/ubuntu/docker/thweb}/models/android"
ssh "$HOST" "mkdir -p '$DIR'"
for f in "$@"; do
    name="$(basename "$f")"
    [[ "$name" =~ _([a-z]+[0-9]+)\.litertlm$ ]] || { echo "can't read the chip from $name" >&2; exit 1; }
    sha="$(shasum -a 256 "$f" | cut -d' ' -f1)"
    rsync -a --partial --progress "$f" "$HOST:$DIR/$name"
    remote="$(ssh "$HOST" "sha256sum '$DIR/$name' | cut -d' ' -f1")"
    [ "$sha" = "$remote" ] || { echo "$name: SHA-256 differs on the server" >&2; exit 1; }
    ssh "$HOST" "echo '$sha' > '$DIR/$name.sha256'"
    echo "published $name ($sha)"
done
# the manifest: every file that has its .sha256 beside it
ssh "$HOST" "cd '$DIR' && python3 - <<'PY'
import json, os, re
chips = {}
for name in sorted(os.listdir('.')):
    m = re.search(r'_([a-z]+[0-9]+)\.litertlm$', name)
    if not m or not os.path.exists(name + '.sha256'):
        continue
    chips[m.group(1)] = {'file': name, 'sha256': open(name + '.sha256').read().strip(), 'size': os.path.getsize(name)}
tmp = 'manifest.json.part'
json.dump({'chips': chips}, open(tmp, 'w'), indent=1)
os.replace(tmp, 'manifest.json')
print('manifest.json:', ', '.join(chips) or '(no chips)')
PY"
