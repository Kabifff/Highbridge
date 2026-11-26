cmake_minimum_required(VERSION 3.18)
project(highbridge_codec2 C)

file(GLOB CODEC2_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/codec2/*.c)
add_library(highbridge_codec2 SHARED codec2_jni.c ${CODEC2_SOURCES})
target_include_directories(highbridge_codec2 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/codec2 ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(highbridge_codec2 PRIVATE -O2 -w -DNDEBUG)
target_compile_definitions(highbridge_codec2 PRIVATE GIT_HASH="upstream-main")
target_link_libraries(highbridge_codec2 m log)
