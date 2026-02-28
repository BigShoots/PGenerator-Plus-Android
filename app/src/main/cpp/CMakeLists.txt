cmake_minimum_required(VERSION 3.22.1)
project(pgeneratorplus_native)

add_library(pgeneratorplus_native SHARED native-hdr.c)

target_link_libraries(pgeneratorplus_native
    android
    log)
