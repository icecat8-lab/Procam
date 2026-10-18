cmake_minimum_required(VERSION 3.22.1)
project("procamera")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        procamera
        SHARED
        native-lib.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_compile_options(procamera PRIVATE
        -O3
        -ffast-math
        -funroll-loops
        -fexceptions
        -Wall
        -Wno-unused-variable
)


if("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
    target_compile_options(procamera PRIVATE -mfpu=neon)
endif()

target_link_libraries(
        procamera
        android
        ${log-lib}
        ${jnigraphics-lib}
)
