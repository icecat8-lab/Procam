cmake_minimum_required(VERSION 3.22.1)
project("procamera")

add_library(
        procamera
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

# Enable optimizations + NEON SIMD where available
target_compile_options(procamera PRIVATE
        -O3
        -ffast-math
        -funroll-loops
        -Wall
        -Wno-unused-variable
)

# ARM 32-bit: enable NEON
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(procamera PRIVATE -mfpu=neon -mfloat-abi=softfp)
endif()

target_link_libraries(
        procamera
        android
        ${log-lib}
)
