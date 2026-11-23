#!/bin/bash
# Puts the LLM test's Neural Engine model (an Apple Archive, CpuThrottlingTest/Benchmark/LLM/LlmNpuModel.swift) where
# thermohammer.gtgroup.dev/models/ serves it, and checks it arrived whole. Safe to run again.
#   deploy/upload_model.sh path/to/gemma3-1b-it-lut4-ctx1280-v1.aar
# The app pins the file's name and SHA-256 (LlmNpuModel.archiveName, .sha256): upload exactly the file they name.
set -euo pipefail
FILE="${1:?usage: upload_model.sh <archive.aar>}"
HOST="${TH_DEPLOY_HOST:-myserver}"
DIR="${TH_DEPLOY_DIR:-/home/ubuntu/docker/thweb}"
NAME="$(basename "$FILE")"
LOCAL_SHA="$(shasum -a 256 "$FILE" | cut -d' ' -f1)"
ssh "$HOST" "mkdir -p '$DIR/models'"
rsync -a --partial --progress "$FILE" "$HOST:$DIR/models/$NAME"
REMOTE_SHA="$(ssh "$HOST" "sha256sum '$DIR/models/$NAME' | cut -d' ' -f1")"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] || { echo "SHA-256 differs on the server: $REMOTE_SHA (local $LOCAL_SHA)"; exit 1; }
echo "OK: $NAME ($LOCAL_SHA) is on the server; it's served once thweb runs with the models volume (deploy/deploy.sh)."
