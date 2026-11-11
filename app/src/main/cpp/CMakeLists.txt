cmake_minimum_required(VERSION 3.22.1)
project(mobile_harness_native C)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Mobile Harness currently supports only arm64-v8a")
endif()

add_library(pocketspawn SHARED pocket_spawn.c)
target_link_options(pocketspawn PRIVATE "-Wl,-z,max-page-size=16384")
