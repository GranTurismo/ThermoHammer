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
# the Arm dot-product kernels, its OpenCL backend for Adreno GPUs (the kernels' sources embedded, the phone's
# OpenCL driver opened at run time by cl_loader.c) and its Vulkan backend for the other GPUs. Its own library
# (libbenchllm.so), 64-bit Arm only: the
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

    # Its Vulkan backend, for the GPUs the OpenCL backend doesn't take (Mali, Xclipse, PowerVR, …). Its shaders become
    # SPIR-V at build time: llama.cpp's generator (vulkan-shaders-gen, built for this computer) runs the NDK's glslc
    # over every variant and writes them out as C++ arrays, as its own CMake does.
    set(GGML_VK ${GGML}/ggml-vulkan)
    set(VK_GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/vk-gen)
    set(VK_HPP ${VK_GEN_DIR}/ggml-vulkan-shaders.hpp)
    set(VK_GEN ${VK_GEN_DIR}/vulkan-shaders-gen)
    file(MAKE_DIRECTORY ${VK_GEN_DIR})
    # the shader extensions this glslc compiles: the backend and its generator build only the variants it can
    set(VK_FEATURES)
    foreach(F coopmat:GL_KHR_cooperative_matrix:GGML_VULKAN_COOPMAT_GLSLC_SUPPORT
              coopmat2:GL_NV_cooperative_matrix2:GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT
              coopmat2_decode_vector:GL_NV_cooperative_matrix_decode_vector:GGML_VULKAN_COOPMAT2_DECODE_VECTOR_GLSLC_SUPPORT
              integer_dot:GL_EXT_integer_dot_product:GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT
              bfloat16:GL_EXT_bfloat16:GGML_VULKAN_BFLOAT16_GLSLC_SUPPORT
              float_e2m1:GL_EXT_float_e2m1:GGML_VULKAN_FLOAT_E2M1_GLSLC_SUPPORT
              float_e4m3:GL_EXT_float_e4m3:GGML_VULKAN_FLOAT_E4M3_GLSLC_SUPPORT)
        string(REPLACE ":" ";" F ${F})
        list(GET F 0 TEST)
        list(GET F 1 EXT)
        list(GET F 2 DEF)
        execute_process(COMMAND ${GLSLC} -o - -fshader-stage=compute --target-env=vulkan1.3
            ${GGML_VK}/vulkan-shaders/feature-tests/${TEST}.comp OUTPUT_QUIET ERROR_VARIABLE GLSLC_ERR)
        if(NOT GLSLC_ERR MATCHES "extension not supported: ${EXT}")
            list(APPEND VK_FEATURES ${DEF})
        endif()
    endforeach()
    # this computer's own compilers, not the NDK's (which the Android toolchain puts first on the search path)
    find_program(HOST_C_COMPILER NAMES cc clang gcc PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH)
    find_program(HOST_CXX_COMPILER NAMES c++ clang++ g++ PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH)
    file(WRITE ${VK_GEN_DIR}/host-toolchain.cmake
        "set(CMAKE_C_COMPILER \"${HOST_C_COMPILER}\")\nset(CMAKE_CXX_COMPILER \"${HOST_CXX_COMPILER}\")\n")
    set(VK_GEN_ARGS)
    foreach(D ${VK_FEATURES})
        list(APPEND VK_GEN_ARGS -D${D}=ON)
    endforeach()
    include(ExternalProject)
    ExternalProject_Add(vulkan-shaders-gen
        SOURCE_DIR ${GGML_VK}/vulkan-shaders
        BINARY_DIR ${VK_GEN_DIR}/build
        CMAKE_ARGS -DCMAKE_TOOLCHAIN_FILE=${VK_GEN_DIR}/host-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_INSTALL_PREFIX=${VK_GEN_DIR} -DCMAKE_INSTALL_BINDIR=.
            ${VK_GEN_ARGS}
        BUILD_BYPRODUCTS ${VK_GEN})
    file(GLOB VK_SHADERS ${GGML_VK}/vulkan-shaders/*.comp)
    file(GLOB VK_GEN_SRC ${GGML_VK}/vulkan-shaders/*.cpp ${GGML_VK}/vulkan-shaders/*.h)
    add_custom_command(OUTPUT ${VK_HPP}
        COMMAND ${VK_GEN} --output-dir ${VK_GEN_DIR}/spv --target-hpp ${VK_HPP}
        DEPENDS ${VK_GEN_SRC} vulkan-shaders-gen)
    set(VK_SHADER_SRC ${VK_HPP})
    foreach(S ${VK_SHADERS})
        get_filename_component(N ${S} NAME)
        add_custom_command(OUTPUT ${VK_GEN_DIR}/${N}.cpp
            DEPFILE ${VK_GEN_DIR}/${N}.cpp.d
            COMMAND ${VK_GEN} --glslc ${GLSLC} --source ${S} --output-dir ${VK_GEN_DIR}/spv --target-hpp ${VK_HPP}
                --target-cpp ${VK_GEN_DIR}/${N}.cpp
            DEPENDS ${S} ${VK_GEN_SRC} ${VK_HPP} vulkan-shaders-gen)
        list(APPEND VK_SHADER_SRC ${VK_GEN_DIR}/${N}.cpp)
    endforeach()
    set(GGML_VULKAN_SRC ${GGML_VK}/ggml-vulkan.cpp ${GGML_VK}/ggml-vulkan-buffers.cpp ${GGML_VK}/ggml-vulkan-debug.cpp
        ${VK_SHADER_SRC})

    add_library(benchllm SHARED ${LLAMA_SRC} ${GGML_BASE_SRC} ${GGML_CPU_SRC} ${GGML_OPENCL_SRC} ${GGML_VULKAN_SRC}
        ${BENCH_CORE}/llm/llm.cpp llm_jni.cpp cl_loader.c)
    target_include_directories(benchllm PRIVATE ${BENCH_CORE}/include ${LLAMA}/include ${LLAMA}/ggml/include ${GGML}
        ${GGML}/ggml-cpu ${LLAMA}/src ${BENCH_CORE}/third_party/OpenCL-Headers ${CL_EMBED}
        ${BENCH_CORE}/third_party/Vulkan-Headers/include ${VK_GEN_DIR}
        ${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include)
    target_compile_definitions(benchllm PRIVATE _XOPEN_SOURCE=600 _GNU_SOURCE GGML_SCHED_MAX_COPIES=4
        GGML_USE_CPU GGML_USE_OPENCL GGML_USE_VULKAN GGML_USE_CPU_REPACK GGML_USE_LLAMAFILE
        GGML_OPENCL_SOA_Q GGML_OPENCL_TARGET_VERSION=300 GGML_OPENCL_USE_ADRENO_KERNELS GGML_OPENCL_EMBED_KERNELS
        ${VK_FEATURES})
    target_compile_options(benchllm PRIVATE -O3 -w -fvisibility=hidden -fvisibility-inlines-hidden)
    set_source_files_properties(${GGML_CPU_SRC} PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod+fp16")
    target_link_libraries(benchllm log dl vulkan)
    target_link_options(benchllm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()
