# benchllm.dll — the LLM test (BenchCore/llm on llama.cpp b11700, BenchCore/third_party/llama.cpp) for Windows,
# cross-compiled on a Mac by llvm-mingw (../build_llm.sh) for x64 and ARM64, the same way the Android build makes
# libbenchllm.so. Statically linked (libc++, libunwind, winpthreads): it needs nothing but Windows' own DLLs, and
# opens the Vulkan loader (vulkan-1.dll) at run time, when the GPU is asked about.
#   CPU: ggml's own CPU backend (its thread pool, no OpenMP) with the x86 AVX2 kernels (x64: needs AVX2, FMA and F16C —
#        every Intel Haswell / AMD Excavator or newer) or the Arm dot-product kernels (ARM64: ARMv8.2 with dotprod and
#        fp16, as on the phones; i8mm is left out, no run-time switch in ggml's CPU backend picks it safely).
#   GPU: ggml-vulkan, its shaders compiled by shaders/CMakeLists.txt (run by build_llm.sh first). Metal and OpenCL are
#        not part of this build.
# Third-party code: optimised in every build, its warnings off.
cmake_minimum_required(VERSION 3.22)
project(benchllm C CXX)

set(CMAKE_CXX_STANDARD 17)
option(BENCHLLM_VULKAN "build ggml's Vulkan backend (needs the shaders from shaders/CMakeLists.txt in VK_GEN_DIR)" ON)
set(VK_GEN_DIR "" CACHE PATH "the generated Vulkan shader sources (shaders/CMakeLists.txt's output)")
set(SPIRV_HEADERS "" CACHE PATH "include directory with spirv/unified1/spirv.hpp (the NDK ships one)")

set(BENCH_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../../BenchCore)
set(LLAMA ${BENCH_CORE}/third_party/llama.cpp)
set(GGML ${LLAMA}/ggml/src)

file(GLOB LLAMA_SRC ${LLAMA}/src/*.cpp ${LLAMA}/src/models/*.cpp)
set(GGML_BASE_SRC ${GGML}/ggml.c ${GGML}/ggml.cpp ${GGML}/ggml-alloc.c ${GGML}/ggml-backend.cpp
    ${GGML}/ggml-backend-meta.cpp ${GGML}/ggml-backend-dl.cpp ${GGML}/ggml-backend-reg.cpp ${GGML}/ggml-opt.cpp
    ${GGML}/ggml-threading.cpp ${GGML}/ggml-quants.c ${GGML}/gguf.cpp)
file(GLOB GGML_CPU_SRC ${GGML}/ggml-cpu/*.c ${GGML}/ggml-cpu/*.cpp ${GGML}/ggml-cpu/tiled/*.cpp)
list(APPEND GGML_CPU_SRC ${GGML}/ggml-cpu/llamafile/sgemm.cpp)
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    list(APPEND GGML_CPU_SRC ${GGML}/ggml-cpu/arch/arm/quants.c ${GGML}/ggml-cpu/arch/arm/repack.cpp)
    set(CPU_FLAGS -march=armv8.2-a+dotprod+fp16)
else()
    list(APPEND GGML_CPU_SRC ${GGML}/ggml-cpu/arch/x86/quants.c ${GGML}/ggml-cpu/arch/x86/repack.cpp)
    set(CPU_FLAGS -mavx2 -mfma -mf16c)
endif()

set(VULKAN_SRC)
set(VULKAN_DEFS)
set(VULKAN_INC)
if(BENCHLLM_VULKAN)
    if(NOT EXISTS ${VK_GEN_DIR}/features.cmake)
        message(FATAL_ERROR "no Vulkan shaders in VK_GEN_DIR='${VK_GEN_DIR}': run build_llm.sh (or -DBENCHLLM_VULKAN=OFF)")
    endif()
    include(${VK_GEN_DIR}/features.cmake)           # VK_FEATURES: the shader extensions glslc compiled
    set(GGML_VK ${GGML}/ggml-vulkan)
    file(GLOB VK_SHADER_SRC ${VK_GEN_DIR}/*.comp.cpp)
    set(VULKAN_SRC ${GGML_VK}/ggml-vulkan.cpp ${GGML_VK}/ggml-vulkan-buffers.cpp ${GGML_VK}/ggml-vulkan-debug.cpp
        ${VK_SHADER_SRC} ${CMAKE_CURRENT_SOURCE_DIR}/vk_loader.cpp)
    set(VULKAN_DEFS GGML_USE_VULKAN ${VK_FEATURES})
    set(VULKAN_INC ${BENCH_CORE}/third_party/Vulkan-Headers/include ${VK_GEN_DIR} ${SPIRV_HEADERS})
endif()

add_library(benchllm SHARED ${LLAMA_SRC} ${GGML_BASE_SRC} ${GGML_CPU_SRC} ${VULKAN_SRC}
    ${BENCH_CORE}/llm/llm.cpp ${CMAKE_CURRENT_SOURCE_DIR}/../benchllm.def)
target_include_directories(benchllm PRIVATE ${BENCH_CORE}/include ${LLAMA}/include ${LLAMA}/ggml/include ${GGML}
    ${GGML}/ggml-cpu ${LLAMA}/src ${VULKAN_INC})
target_compile_definitions(benchllm PRIVATE GGML_SCHED_MAX_COPIES=4 GGML_USE_CPU GGML_USE_CPU_REPACK GGML_USE_LLAMAFILE
    ${VULKAN_DEFS} _WIN32_WINNT=0x0A00 WINVER=0x0A00 NOMINMAX _CRT_SECURE_NO_WARNINGS)
target_compile_options(benchllm PRIVATE -O3 -w -ffunction-sections -fdata-sections)
# the CPU kernels use the instructions the app checks for before it starts the test; nothing else is built with them
set_source_files_properties(${GGML_CPU_SRC} PROPERTIES COMPILE_OPTIONS "${CPU_FLAGS}")
# llm.cpp is written for POSIX (setenv)
set_source_files_properties(${BENCH_CORE}/llm/llm.cpp PROPERTIES COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/win_compat.h")
set_target_properties(benchllm PROPERTIES PREFIX "")
target_link_options(benchllm PRIVATE -static -s -Wl,--gc-sections)
