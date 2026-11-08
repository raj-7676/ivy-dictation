# Ivy phone engine: llama.cpp (patched copy in ./llama.cpp) + ivy_engine. Built for the PC test (ivy_test) and,
# through app/src/main/cpp, for Android (libivy.so).
cmake_minimum_required(VERSION 3.22)
project(ivy_native CXX C)
set(CMAKE_CXX_STANDARD 17)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)    # just libmtmd (audio), not the tools tree
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
if (ANDROID)
    # Universal: every ARM CPU level is built as its own libggml-cpu-android_*.so and the phone loads the best one
    # its chip supports at startup (plain ARMv8.0 up to ARMv9 + SME). Needs shared libraries.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(llama.cpp)

add_library(ivy_engine STATIC ivy_engine.cpp)
target_include_directories(ivy_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} llama.cpp/tools/mtmd)
target_link_libraries(ivy_engine PUBLIC mtmd llama)

if (ANDROID)
    add_library(ivy SHARED ivy_jni.cpp)   # -> app/src/main/jniLibs/arm64-v8a/libivy.so
    target_link_libraries(ivy PRIVATE ivy_engine log)
    target_link_options(ivy PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
add_executable(ivy_test ivy_test.cpp)   # PC test; on Android a command-line copy for adb shell profiling
target_link_libraries(ivy_test PRIVATE ivy_engine)
