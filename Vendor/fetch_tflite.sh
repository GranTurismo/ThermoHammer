#!/bin/bash
# Fetches TensorFlow Lite 2.17.0 for iOS (the AI test's CPU and GPU runs: XNNPACK and the Metal delegate) into
# Vendor/TensorFlowLiteC — the prebuilt static frameworks Google publishes for its CocoaPods pod, checked against
# a pinned SHA-256. Too big for the repo (≈130 MB), so run this once before building the iOS app.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
URL="https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
SHA="9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
DEST="$HERE/TensorFlowLiteC"
if [ -d "$DEST/TensorFlowLiteC.xcframework" ] && [ -d "$DEST/TensorFlowLiteCMetal.xcframework" ]; then
    echo "TensorFlowLiteC already in $DEST"; exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --retry 3 -o "$TMP/tflite.tar.gz" "$URL"
echo "$SHA  $TMP/tflite.tar.gz" | shasum -a 256 -c -
tar -xzf "$TMP/tflite.tar.gz" -C "$TMP"
mkdir -p "$DEST"
cp -R "$TMP"/TensorFlowLiteC-2.17.0/Frameworks/TensorFlowLiteC.xcframework "$TMP"/TensorFlowLiteC-2.17.0/Frameworks/TensorFlowLiteCMetal.xcframework "$DEST/"
echo "TensorFlowLiteC 2.17.0 → $DEST"
