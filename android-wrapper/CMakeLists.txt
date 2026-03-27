cmake_minimum_required(VERSION 3.13)
project(executorch_android_wrapper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Point to the existing build tree with your static libs
set(EXECUTORCH_BUILD_DIR "${CMAKE_CURRENT_LIST_DIR}/../cmake-out-android-arm64-v8a")

# Core runtime static library
set(EXECUTORCH_CORE_LIB
    "${EXECUTORCH_BUILD_DIR}/libexecutorch_core.a"
)

# Extra libs to fold in (QNN, XNNPACK, Vulkan, etc.)
set(EXECUTORCH_EXTRA_LIBS
    "${EXECUTORCH_BUILD_DIR}/libexecutorch.a"
    "${EXECUTORCH_BUILD_DIR}/backends/vulkan/libvulkan_backend.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/libxnnpack_backend.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/third-party/XNNPACK/libXNNPACK.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/third-party/XNNPACK/libxnnpack-microkernels-prod.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/third-party/cpuinfo/libcpuinfo.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/third-party/cpuinfo/libcpuinfo_internals.a"
    "${EXECUTORCH_BUILD_DIR}/backends/xnnpack/third-party/pthreadpool/libpthreadpool.a"
)

add_library(executorch_android SHARED dummy.cpp)

target_link_libraries(executorch_android
    PRIVATE
        ${EXECUTORCH_CORE_LIB}
        ${EXECUTORCH_EXTRA_LIBS}
        log
        dl
)

# Ensure SONAME is libexecutorch.so so Android System.loadLibrary("executorch") works
set_target_properties(executorch_android PROPERTIES
    OUTPUT_NAME "executorch"
)