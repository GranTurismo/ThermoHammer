# benchllm.dll's Vulkan shaders, built once on this computer for both Windows architectures (SPIR-V does not depend
# on the CPU): llama.cpp's generator (vulkan-shaders-gen, a program for the Mac running this) runs the NDK's glslc over
# every variant of every .comp shader and writes them out as C++ arrays — what ggml-vulkan's own CMake does, and what
# the Android build does. build_llm.sh runs this first; ../CMakeLists.txt compiles what comes out of VK_GEN_DIR.
# Not a cross build: this project uses the Mac's own compilers.
cmake_minimum_required(VERSION 3.22)
project(benchllm_shaders C CXX)

set(GLSLC "" CACHE FILEPATH "glslc (the Android NDK ships one: shader-tools/<host>/glslc)")
set(VK_GEN_DIR "" CACHE PATH "where the generated shader sources go")
if(NOT GLSLC OR NOT VK_GEN_DIR)
    message(FATAL_ERROR "pass -DGLSLC=... -DVK_GEN_DIR=...")
endif()
set(GGML_VK ${CMAKE_CURRENT_SOURCE_DIR}/../../../../BenchCore/third_party/llama.cpp/ggml/src/ggml-vulkan)

# the shader extensions this glslc compiles: the generator builds only the variants it can, and ggml-vulkan.cpp
# (compiled with the same list, written to features.cmake) must agree
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
        set(${DEF} ON CACHE BOOL "" FORCE)      # read by vulkan-shaders/CMakeLists.txt
    endif()
endforeach()
file(MAKE_DIRECTORY ${VK_GEN_DIR})
file(WRITE ${VK_GEN_DIR}/features.cmake "set(VK_FEATURES ${VK_FEATURES})\n")

set(CMAKE_BUILD_TYPE Release)
add_subdirectory(${GGML_VK}/vulkan-shaders ${CMAKE_CURRENT_BINARY_DIR}/gen)

set(VK_HPP ${VK_GEN_DIR}/ggml-vulkan-shaders.hpp)
set(VK_GEN $<TARGET_FILE:vulkan-shaders-gen>)
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
add_custom_target(shaders ALL DEPENDS ${VK_SHADER_SRC})
