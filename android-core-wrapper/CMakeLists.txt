cmake_minimum_required(VERSION 3.13)
project(executorch_android_core LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(EXECUTORCH_SDK_DIR "${CMAKE_CURRENT_LIST_DIR}/../build-android")

set(EXECUTORCH_CORE_LIB
    "${EXECUTORCH_SDK_DIR}/lib/libexecutorch_core.a"
)

set(EXECUTORCH_EXTRA_LIBS
    "${EXECUTORCH_SDK_DIR}/lib/libexecutorch.a"
)

add_library(executorch_android SHARED dummy.cpp)

target_link_libraries(executorch_android
    PRIVATE
        ${EXECUTORCH_CORE_LIB}
        ${EXECUTORCH_EXTRA_LIBS}
        log
        dl
)

set_target_properties(executorch_android PROPERTIES
    OUTPUT_NAME "executorch"
)