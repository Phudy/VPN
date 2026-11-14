cmake_minimum_required(VERSION 3.10)
project(redline_whisper)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

find_library(LOG_LIB log)
include(FetchContent)

# Two builds like upstream's Android example: a portable arm64 one, and one using
# ARMv8.2 fp16 instructions (2-3x faster on most phones since ~2018). Picked at runtime.
function(build_library target_name)
    add_library(${target_name} SHARED ${WHISPER_DIR}/src/whisper.cpp ${CMAKE_SOURCE_DIR}/whisper_jni.cpp)
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    if (${target_name} STREQUAL "whisper_fp16")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    endif ()
    target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_fp16")
endif ()
build_library("whisper")

include_directories(${WHISPER_DIR}/src ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include ${WHISPER_DIR}/ggml/src ${WHISPER_DIR}/ggml/src/ggml-cpu)
