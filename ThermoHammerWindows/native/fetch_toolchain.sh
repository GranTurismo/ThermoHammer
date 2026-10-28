#!/bin/bash
# Fetches llvm-mingw (clang + MinGW-w64, UCRT) for macOS into native/.toolchain — the cross-compiler that builds
# benchcore.dll for Windows x64 and ARM64 on a Mac. Checked against a pinned SHA-256; run by build.sh when missing.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VER="20260922"
NAME="llvm-mingw-$VER-ucrt-macos-universal"
URL="https://github.com/mstorsjo/llvm-mingw/releases/download/$VER/$NAME.tar.xz"
SHA="52e5f5a7b131021d0c39a37a38fa380a1da7885cd04bd61afd0cd4ecfb8bc1f3"
DEST="$HERE/.toolchain"
if [ -x "$DEST/$NAME/bin/clang" ]; then echo "$DEST/$NAME"; exit 0; fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --retry 3 -o "$TMP/t.tar.xz" "$URL" >&2
echo "$SHA  $TMP/t.tar.xz" | shasum -a 256 -c - >&2
mkdir -p "$DEST"
tar -xJf "$TMP/t.tar.xz" -C "$DEST"
echo "$DEST/$NAME"
