# benchcore.dll — BenchCore (the CPU, MEM and QR tests: the same C++ as the Android and iOS apps) for Windows,
# cross-compiled on a Mac by llvm-mingw (build.sh) for x64 and ARM64. Statically linked (libc++, libunwind), so it
# needs nothing but Windows' own UCRT. Optimised in every build: the kernels are what's measured.
cmake_minimum_required(VERSION 3.22)
project(benchcore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BENCH_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../BenchCore)
set(ZXING ${BENCH_CORE}/third_party/zxing-cpp/src)
set(ZLIB ${BENCH_CORE}/third_party/zlib)

file(GLOB BENCH_CORE_SRC ${BENCH_CORE}/src/*.cpp)
file(GLOB ZXING_SRC ${ZXING}/*.cpp ${ZXING}/qrcode/*.cpp ${ZXING}/libzueci/zueci.c)
# Android and iOS link the system's zlib; Windows has none to link, so the vendored 1.3.1 goes in
file(GLOB ZLIB_SRC ${ZLIB}/*.c)

add_library(benchcore SHARED ${BENCH_CORE_SRC} ${ZXING_SRC} ${ZLIB_SRC} ${CMAKE_CURRENT_SOURCE_DIR}/benchcore.def)
target_include_directories(benchcore PRIVATE ${BENCH_CORE}/include ${ZXING} ${ZLIB})
target_compile_options(benchcore PRIVATE -O3)
set_target_properties(benchcore PROPERTIES PREFIX "")
target_link_options(benchcore PRIVATE -static -s)

# third-party code: warnings off
set(THIRD_PARTY_FLAGS -w)
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    # the ARMv8 crypto paths, used only when Windows reports the extension
    set_source_files_properties(${BENCH_CORE}/src/crypto_arm.cpp PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
else()
    # the QR frames: Arm fuses multiply-adds, so on x64 the renderer (and zxing-cpp's perspective transform it calls)
    # is built with FMA3 to draw the same pixels; bc_qr_supported() turns away CPUs without it
    set(THIRD_PARTY_FLAGS -w -mavx -mfma)
    set_source_files_properties(${BENCH_CORE}/src/qr.cpp PROPERTIES COMPILE_OPTIONS "-mavx;-mfma")
    set_source_files_properties(${BENCH_CORE}/src/bench_core.cpp PROPERTIES COMPILE_DEFINITIONS BC_QR_FMA)
endif()
set_source_files_properties(${ZXING_SRC} PROPERTIES COMPILE_OPTIONS "${THIRD_PARTY_FLAGS}")
set_source_files_properties(${ZLIB_SRC} PROPERTIES COMPILE_OPTIONS "-w")
