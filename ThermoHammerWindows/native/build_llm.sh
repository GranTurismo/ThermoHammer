#!/bin/bash
# Builds benchllm.dll (the LLM test: llama.cpp with its CPU and Vulkan backends) for Windows x64 and ARM64 on a Mac
# (llvm-mingw, fetched once by fetch_toolchain.sh) into runtimes/win-<arch>/native/, next to benchcore.dll.
# Usage: native/build_llm.sh [x64|arm64]...      Needs the Android NDK (its glslc compiles the Vulkan shaders,
# which are generated once, by a program built for this Mac, and shared by both architectures); set ANDROID_NDK to
# pick one, otherwise the newest under ~/Library/Android/sdk/ndk is used. Without an NDK: BENCHLLM_VULKAN=0 builds
# the CPU-only DLL (bc_llm_gpu_name() and bc_llm_gpu_api() answer "").
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="$("$HERE/fetch_toolchain.sh")"
CMAKE="$(command -v cmake)"
NINJA="$(ls "$HOME"/Library/Android/sdk/cmake/*/bin/ninja 2>/dev/null | head -1 || true)"
[ -n "$NINJA" ] || NINJA="$(command -v ninja)"
ARCHS=("$@"); [ ${#ARCHS[@]} -gt 0 ] || ARCHS=(x64 arm64)

VULKAN="${BENCHLLM_VULKAN:-1}"
GEN="$HERE/.build/llm-vk-gen"
VK_ARGS=(-DBENCHLLM_VULKAN=OFF)
if [ "$VULKAN" != 0 ]; then
    NDK="${ANDROID_NDK:-}"
    [ -n "$NDK" ] || NDK="$(ls -d "$HOME"/Library/Android/sdk/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
    NDK="${NDK%/}"
    GLSLC="${GLSLC:-$(ls "$NDK"/shader-tools/darwin-x86_64/glslc 2>/dev/null || true)}"   # GLSLC=...: a newer one (the NDK's has no
                                                                                          # cooperative-matrix or integer-dot shaders)
    SPIRV="$NDK/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
    if [ -z "$GLSLC" ] || [ ! -f "$SPIRV/spirv/unified1/spirv.hpp" ]; then
        echo "no Android NDK with glslc and the SPIR-V headers (set ANDROID_NDK, or BENCHLLM_VULKAN=0)" >&2; exit 1
    fi
    # the shaders: SPIR-V as C++ arrays, once for every architecture (a host build: the Mac's own compilers)
    "$CMAKE" -S "$HERE/llm/shaders" -B "$HERE/.build/llm-shaders" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=/usr/bin/cc -DCMAKE_CXX_COMPILER=/usr/bin/c++ \
        -DGLSLC="$GLSLC" -DVK_GEN_DIR="$GEN" >/dev/null
    "$CMAKE" --build "$HERE/.build/llm-shaders"
    VK_ARGS=(-DBENCHLLM_VULKAN=ON -DVK_GEN_DIR="$GEN" -DSPIRV_HEADERS="$SPIRV")
fi

for A in "${ARCHS[@]}"; do
    case "$A" in x64) T=x86_64 ;; arm64) T=aarch64 ;; *) echo "unknown arch $A" >&2; exit 1 ;; esac
    B="$HERE/.build/llm-$A"
    "$CMAKE" -S "$HERE/llm" -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=$T \
        -DCMAKE_C_COMPILER="$TC/bin/$T-w64-mingw32-clang" -DCMAKE_CXX_COMPILER="$TC/bin/$T-w64-mingw32-clang++" \
        -DCMAKE_RC_COMPILER="$TC/bin/$T-w64-mingw32-windres" "${VK_ARGS[@]}" >/dev/null
    "$CMAKE" --build "$B"
    mkdir -p "$HERE/../runtimes/win-$A/native"
    cp "$B/benchllm.dll" "$HERE/../runtimes/win-$A/native/"
    echo "benchllm.dll ($A) → runtimes/win-$A/native/"
done
