cmake_minimum_required(VERSION 3.22.1)
project(cheese_assistant LANGUAGES CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PIKAFISH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/pikafish/src)

file(GLOB PIKAFISH_SOURCES ${PIKAFISH_DIR}/*.cpp)
list(FILTER PIKAFISH_SOURCES EXCLUDE REGEX ".*/main\\.cpp$")
file(GLOB_RECURSE PIKAFISH_NNUE_SOURCES ${PIKAFISH_DIR}/nnue/*.cpp)

file(GLOB ZSTD_SOURCES
        ${PIKAFISH_DIR}/external/decompress/*.cpp
        ${PIKAFISH_DIR}/external/common/*.cpp)

add_library(cheese_engine SHARED
        ${PIKAFISH_SOURCES}
        ${PIKAFISH_NNUE_SOURCES}
        ${ZSTD_SOURCES}
        pikafish_jni.cpp)

target_include_directories(cheese_engine PRIVATE
        ${PIKAFISH_DIR} ${PIKAFISH_DIR}/nnue ${PIKAFISH_DIR}/external ${PIKAFISH_DIR}/external/decompress ${PIKAFISH_DIR}/external/common)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    # 对应官方 Makefile armv8 目标：popcnt + neon(=8)；dotprod 可选，先不开保证兼容
    target_compile_options(cheese_engine PRIVATE -march=armv8-a)
    target_compile_definitions(cheese_engine PRIVATE
            IS_64BIT USE_POPCNT USE_NEON=8 NO_PREFETCH USE_PTHREADS NNUE_EMBEDDING_OFF)
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    # 模拟器用：对应官方 x86-64-sse41-popcnt 目标（不用 bmi2，兼容老模拟器 CPU）
    target_compile_options(cheese_engine PRIVATE -msse4.1)
    target_compile_definitions(cheese_engine PRIVATE
            IS_64BIT USE_POPCNT USE_SSE2 NO_PREFETCH USE_PTHREADS NNUE_EMBEDDING_OFF ZSTD_DISABLE_ASM)
endif()

find_library(log-lib log)
target_link_libraries(cheese_engine ${log-lib})
