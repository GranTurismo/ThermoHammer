# Every module with native code in a bundle must cover the app's ABIs, but the NPU runtimes exist for arm64 only: for the
# others each NPU module gets this empty library, which nothing loads.
cmake_minimum_required(VERSION 3.22.1)
project(npu_placeholder C)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(thnpu_${MODULE} SHARED placeholder.c)
endif()
