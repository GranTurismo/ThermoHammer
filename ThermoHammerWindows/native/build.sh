#!/bin/bash
# Builds benchcore.dll for Windows x64 and ARM64 on a Mac (llvm-mingw, fetched once by fetch_toolchain.sh) into
# runtimes/win-<arch>/native/, where the app project picks it up. Usage: native/build.sh [x64|arm64]...
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$("$HERE/fetch_toolchain.sh")"
CMAKE="$(command -v cmake)"
NINJA="$(ls "$HOME"/Library/Android/sdk/cmake/*/bin/ninja 2>/dev/null | head -1 || true)"
[ -n "$NINJA" ] || NINJA="$(command -v ninja)"
ARCHS=("$@"); [ ${#ARCHS[@]} -gt 0 ] || ARCHS=(x64 arm64)
for A in "${ARCHS[@]}"; do
    case "$A" in x64) T=x86_64 ;; arm64) T=aarch64 ;; *) echo "unknown arch $A" >&2; exit 1 ;; esac
    B="$HERE/.build/$A"
    "$CMAKE" -S "$HERE" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=$T \
        -DCMAKE_C_COMPILER="$TC/bin/$T-w64-mingw32-clang" -DCMAKE_CXX_COMPILER="$TC/bin/$T-w64-mingw32-clang++" \
        -DCMAKE_RC_COMPILER="$TC/bin/$T-w64-mingw32-windres" >/dev/null
    "$CMAKE" --build "$B"
    mkdir -p "$HERE/../runtimes/win-$A/native"
    cp "$B/benchcore.dll" "$HERE/../runtimes/win-$A/native/"
    echo "benchcore.dll ($A) → runtimes/win-$A/native/"
done
