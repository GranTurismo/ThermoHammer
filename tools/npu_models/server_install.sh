#!/bin/bash
# Runs on the server, from the CI job (.github/workflows/npu-models.yml), inside ~/docker/thweb/.npu-incoming (outside what
# the site serves): moves every model file whose SHA-256 still matches into the site's models/android/, then rebuilds
# manifest.json. A file is moved whole, so a phone never downloads half of one under the published name.
set -euo pipefail
IN="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$IN")/models/android"
mkdir -p "$OUT"
cd "$IN"
for f in Gemma3-1B-IT_q*_ekv1280_*.litertlm; do
    [ -f "$f" ] || continue
    if [ -f "$f.sha256" ] && [ "$(sha256sum "$f" | cut -d' ' -f1)" = "$(cat "$f.sha256")" ]; then
        chmod 644 "$f" "$f.sha256"
        mv "$f" "$OUT/$f"
        mv "$f.sha256" "$OUT/$f.sha256"
        echo "published $f"
    else
        echo "$f: SHA-256 differs after the copy, not published"
        rm -f "$f" "$f.sha256"
    fi
done
python3 -I "$IN/manifest.py" "$OUT"
