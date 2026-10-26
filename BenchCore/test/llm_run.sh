#!/bin/bash
# Builds and runs the LLM host test on a Mac (Apple silicon): llama.cpp with its CPU and Metal backends, the same
# sources and flags as the apps. Objects are kept between runs. Args go to the test: "cpu" or "gpu" runs only one.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LL="$HERE/third_party/llama.cpp"
OUT="${TMPDIR:-/tmp}/bench_llm_test"
JOBS="$(sysctl -n hw.ncpu)"
mkdir -p "$OUT.o"

ARCH="-march=armv8.2-a+dotprod+fp16"              # the phones' CPU kernels: Arm dot product, no i8mm
INC="-I$LL/include -I$LL/ggml/include -I$LL/ggml/src -I$LL/ggml/src/ggml-cpu -I$LL/src -I$HERE/include"
GGML_DEFS="-D_XOPEN_SOURCE=600 -D_DARWIN_C_SOURCE -DGGML_SCHED_MAX_COPIES=4 -DGGML_USE_CPU -DGGML_USE_METAL"
CPU_DEFS="-DGGML_USE_CPU_REPACK -DGGML_USE_LLAMAFILE"
KERN="$LL/ggml/src/ggml-metal/kernels"

build() {   # source, extra flags…
    local src="$1"; shift
    local obj="$OUT.o/$(echo "${src#$HERE/}" | tr '/' '_').o"
    [ -f "$obj" ] && [ ! "$src" -nt "$obj" ] && return 0
    case "$src" in
        *.c) cc -std=gnu11 -O3 -w $ARCH $INC "$@" -c "$src" -o "$obj" ;;
        *.m) cc -O3 -w -fno-objc-arc $INC "$@" -c "$src" -o "$obj" ;;
        *.metal) xcrun -sdk macosx metal -O3 -I "$LL/ggml/src" -I "$LL/ggml/src/ggml-metal" -c "$src" \
                     -o "$OUT.o/$(basename "$src" .metal).air" ;;
        *) c++ -std=gnu++17 -O3 -w $ARCH $INC "$@" -c "$src" -o "$obj" ;;
    esac
}

sources() {
    for f in "$LL"/src/*.cpp "$LL"/src/models/*.cpp; do echo "$f"; done
    for f in ggml.c ggml.cpp ggml-alloc.c ggml-backend.cpp ggml-backend-meta.cpp ggml-backend-dl.cpp ggml-backend-reg.cpp \
             ggml-opt.cpp ggml-threading.cpp ggml-quants.c gguf.cpp; do echo "$LL/ggml/src/$f $GGML_DEFS"; done
    for f in "$LL"/ggml/src/ggml-cpu/*.c "$LL"/ggml/src/ggml-cpu/*.cpp "$LL"/ggml/src/ggml-cpu/tiled/*.cpp \
             "$LL"/ggml/src/ggml-cpu/llamafile/sgemm.cpp "$LL"/ggml/src/ggml-cpu/arch/arm/quants.c \
             "$LL"/ggml/src/ggml-cpu/arch/arm/repack.cpp; do echo "$f $GGML_DEFS $CPU_DEFS"; done
    for f in "$LL"/ggml/src/ggml-metal/*.cpp "$LL"/ggml/src/ggml-metal/*.m; do echo "$f $GGML_DEFS"; done
    # the Metal kernels, compiled ahead into the default.metallib ggml finds next to the binary (fa_common and
    # fa_vec_common are included by the others, not compiled)
    for f in "$KERN"/*.metal; do case "$f" in *_common.metal) ;; *) echo "$f" ;; esac; done
    echo "$HERE/llm/llm.cpp"
    echo "$HERE/test/llm_test.cpp"
}

failed="$OUT.o/failed"
rm -f "$failed"
while read -r line; do
    ( build $line || touch "$failed" ) &
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do sleep 0.05; done
done < <(sources)
wait
[ -f "$failed" ] && { echo "build failed"; exit 1; }

if [ ! -f "$OUT.o/default.metallib" ] || [ -n "$(find "$OUT.o" -name '*.air' -newer "$OUT.o/default.metallib" -print -quit)" ]; then
    xcrun -sdk macosx metallib "$OUT.o"/*.air -o "$OUT.o/default.metallib"
fi
c++ "$OUT.o"/*.o -framework Foundation -framework Metal -framework MetalKit -o "$OUT.o/llm_test"
"$OUT.o/llm_test" "$@"
