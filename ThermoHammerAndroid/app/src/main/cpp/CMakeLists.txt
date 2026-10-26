# Neon City RT — GPU Test 3: the Vulkan ray-query renderer (libneonrt.so).
cmake_minimum_required(VERSION 3.22.1)
project(neonrt C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# GLSL → SPIR-V with the NDK's glslc, emitted as C array initialisers and compiled into the library.
if(CMAKE_HOST_APPLE)
    set(HOST_TAG darwin-x86_64)
elseif(CMAKE_HOST_WIN32)
    set(HOST_TAG windows-x86_64)
else()
    set(HOST_TAG linux-x86_64)
endif()
set(GLSLC ${ANDROID_NDK}/shader-tools/${HOST_TAG}/glslc)
set(SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)
set(SPV_DIR ${CMAKE_CURRENT_BINARY_DIR}/spv)
file(MAKE_DIRECTORY ${SPV_DIR})
file(GLOB SHADER_INCLUDES ${SHADER_DIR}/*.glsl)

set(SPV_OUTPUTS)
# name, source, target env, extra defines
function(add_spirv NAME SRC ENV)
    set(OUT ${SPV_DIR}/${NAME}.inc)
    add_custom_command(
        OUTPUT ${OUT}
        COMMAND ${GLSLC} --target-env=${ENV} -O -mfmt=c -I${SHADER_DIR} ${ARGN} ${SHADER_DIR}/${SRC} -o ${OUT}
        DEPENDS ${SHADER_DIR}/${SRC} ${SHADER_INCLUDES}
        COMMENT "glslc ${SRC} -> ${NAME}"
        VERBATIM)
    set(SPV_OUTPUTS ${SPV_OUTPUTS} ${OUT} PARENT_SCOPE)
endfunction()

foreach(S scene.vert sky.vert glow.vert rain.vert sky.frag glow.frag rain.frag
          combine.comp down.comp up.comp final.comp)
    string(REPLACE "." "_" N ${S})
    add_spirv(${N} ${S} vulkan1.1)
endforeach()
# shaders that cast rays: a plain build (development on GPUs without ray queries) and an RT build
add_spirv(scene_frag scene.frag vulkan1.1)
add_spirv(fog_comp fog.comp vulkan1.1)
add_spirv(scene_frag_rt scene.frag vulkan1.2 -DRT)
add_spirv(fog_comp_rt fog.comp vulkan1.2 -DRT)
add_custom_target(neonrt_shaders DEPENDS ${SPV_OUTPUTS})

add_library(neonrt SHARED vk_loader.cpp neon_rt.cpp jni_bridge.cpp)
add_dependencies(neonrt neonrt_shaders)
target_include_directories(neonrt PRIVATE ${SPV_DIR})
target_compile_definitions(neonrt PRIVATE VK_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(neonrt PRIVATE -Wall -Wno-unused-function -Wno-missing-braces)
target_link_libraries(neonrt android log dl)

# CPU and MEM tests and the QR scanning test: BenchCore — the shared C++ code, also built for iOS — behind a
# small JNI layer (libbenchcore.so). Optimised in debug builds too: the kernels are what's measured. The ARMv8
# crypto paths are compiled with the extension and only used when the CPU reports it. The QR reader and writer
# are vendored zxing-cpp (third-party code, so its warnings are off).
set(BENCH_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../BenchCore)
set(ZXING ${BENCH_CORE}/third_party/zxing-cpp/src)
file(GLOB BENCH_CORE_SRC ${BENCH_CORE}/src/*.cpp)
file(GLOB ZXING_SRC ${ZXING}/*.cpp ${ZXING}/qrcode/*.cpp ${ZXING}/libzueci/zueci.c)
set_source_files_properties(${ZXING_SRC} PROPERTIES COMPILE_OPTIONS "-w")
add_library(benchcore SHARED ${BENCH_CORE_SRC} ${ZXING_SRC} cpu_jni.cpp mem_jni.cpp qr_jni.cpp)
target_include_directories(benchcore PRIVATE ${BENCH_CORE}/include ${ZXING})
target_compile_options(benchcore PRIVATE -O3 -Wall)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set_source_files_properties(${BENCH_CORE}/src/crypto_arm.cpp PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
endif()
target_link_libraries(benchcore z dl)

# The LLM test: llama.cpp (BenchCore/third_party/llama.cpp, b11700) running BenchCore/llm — its CPU backend with
# the Arm dot-product kernels and its OpenCL backend for Adreno GPUs, with the kernels' sources embedded and the
# phone's OpenCL driver opened at run time (cl_loader.c). Its own library (libbenchllm.so), 64-bit Arm only: the
# other ABIs get none, and the test reports it can't run. Third-party code: optimised in debug builds too, its
# warnings off, its symbols hidden.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(LLAMA ${BENCH_CORE}/third_party/llama.cpp)
    set(GGML ${LLAMA}/ggml/src)
    file(GLOB LLAMA_SRC ${LLAMA}/src/*.cpp ${LLAMA}/src/models/*.cpp)
    set(GGML_BASE_SRC ${GGML}/ggml.c ${GGML}/ggml.cpp ${GGML}/ggml-alloc.c ${GGML}/ggml-backend.cpp
        ${GGML}/ggml-backend-meta.cpp ${GGML}/ggml-backend-dl.cpp ${GGML}/ggml-backend-reg.cpp ${GGML}/ggml-opt.cpp
        ${GGML}/ggml-threading.cpp ${GGML}/ggml-quants.c ${GGML}/gguf.cpp)
    file(GLOB GGML_CPU_SRC ${GGML}/ggml-cpu/*.c ${GGML}/ggml-cpu/*.cpp ${GGML}/ggml-cpu/tiled/*.cpp)
    list(APPEND GGML_CPU_SRC ${GGML}/ggml-cpu/llamafile/sgemm.cpp ${GGML}/ggml-cpu/arch/arm/quants.c
        ${GGML}/ggml-cpu/arch/arm/repack.cpp)
    set(GGML_OPENCL_SRC ${GGML}/ggml-opencl/ggml-opencl.cpp ${GGML}/ggml-opencl/cl-program-cache.cpp)

    # every OpenCL kernel as a C++ raw string the backend #includes (what its embed_kernel.py does)
    set(CL_EMBED ${CMAKE_CURRENT_BINARY_DIR}/cl-kernels)
    file(MAKE_DIRECTORY ${CL_EMBED})
    file(GLOB CL_KERNELS ${GGML}/ggml-opencl/kernels/*.cl)
    foreach(K ${CL_KERNELS})
        get_filename_component(N ${K} NAME)
        file(READ ${K} BODY)
        file(WRITE ${CL_EMBED}/${N}.h.tmp "R\"CLKERNEL(${BODY})CLKERNEL\"\n")
        file(COPY_FILE ${CL_EMBED}/${N}.h.tmp ${CL_EMBED}/${N}.h ONLY_IF_DIFFERENT)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${K})
    endforeach()

    add_library(benchllm SHARED ${LLAMA_SRC} ${GGML_BASE_SRC} ${GGML_CPU_SRC} ${GGML_OPENCL_SRC}
        ${BENCH_CORE}/llm/llm.cpp llm_jni.cpp cl_loader.c)
    target_include_directories(benchllm PRIVATE ${BENCH_CORE}/include ${LLAMA}/include ${LLAMA}/ggml/include ${GGML}
        ${GGML}/ggml-cpu ${LLAMA}/src ${BENCH_CORE}/third_party/OpenCL-Headers ${CL_EMBED})
    target_compile_definitions(benchllm PRIVATE _XOPEN_SOURCE=600 _GNU_SOURCE GGML_SCHED_MAX_COPIES=4
        GGML_USE_CPU GGML_USE_OPENCL GGML_USE_CPU_REPACK GGML_USE_LLAMAFILE
        GGML_OPENCL_SOA_Q GGML_OPENCL_TARGET_VERSION=300 GGML_OPENCL_USE_ADRENO_KERNELS GGML_OPENCL_EMBED_KERNELS)
    target_compile_options(benchllm PRIVATE -O3 -w -fvisibility=hidden -fvisibility-inlines-hidden)
    set_source_files_properties(${GGML_CPU_SRC} PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod+fp16")
    target_link_libraries(benchllm log dl)
    target_link_options(benchllm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()
