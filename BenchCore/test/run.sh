#!/bin/bash
# Builds and runs the BenchCore host test (clang or gcc, zlib). Extra args go to the test (e.g. --emit).
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/bench_core_test"
ZX="$HERE/third_party/zxing-cpp/src"
mkdir -p "$OUT.zx" && cd "$OUT.zx"
cc -O2 -w -c "$ZX/libzueci/zueci.c"
c++ -std=c++17 -O2 -w -I"$ZX" -c "$ZX"/*.cpp "$ZX"/qrcode/*.cpp
c++ -std=c++17 -O2 -Wall -I"$HERE/include" -I"$ZX" "$HERE"/src/*.cpp "$HERE/test/core_test.cpp" "$OUT.zx"/*.o -lz -o "$OUT"
"$OUT" "$@"
