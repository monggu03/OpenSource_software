cmake_minimum_required(VERSION 3.22.1)
project("safewalk")

set(OpenCV_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../libs/OpenCV-android-sdk/sdk/native/jni")
find_package(OpenCV REQUIRED)

message(STATUS "OpenCV found: ${OpenCV_LIBS}")

add_library(
    safewalk_native
    SHARED
    native_lib.cpp
    image_processor.cpp
)

target_include_directories(safewalk_native PRIVATE
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_SOURCE_DIR}
)

target_link_libraries(
    safewalk_native
    ${OpenCV_LIBS}
    log
    android
    jnigraphics
)