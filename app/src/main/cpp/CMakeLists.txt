cmake_minimum_required(VERSION 3.22.1)
project("procamera")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fopenmp")

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME} ${log-lib})
