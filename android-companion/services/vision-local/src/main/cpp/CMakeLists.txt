cmake_minimum_required(VERSION 3.22.1)
project(vision_local LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CPU-only static llama.cpp + mtmd, linked into one libvision_local.so. The llama.cpp sources are the SAME git submodule :services:ai builds
# (android-companion/services/ai/src/main/cpp/llama.cpp, pinned by its gitlink, MIT): no second copy, nothing vendored or fetched here.
set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../ai/src/main/cpp/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp submodule is missing: run `git submodule update --init` (expected ${LLAMA_DIR})")
endif()

set(VLM_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "ggml -march for arm64 (dotprod, i8mm)")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "${VLM_ARM_ARCH}" CACHE STRING "" FORCE)
foreach(gpu GGML_VULKAN GGML_OPENCL GGML_CUDA GGML_METAL GGML_HIP GGML_SYCL GGML_WEBGPU GGML_HEXAGON GGML_KOMPUTE GGML_RPC)
    set(${gpu} OFF CACHE BOOL "" FORCE)
endforeach()
message(STATUS "vision_local: GGML_CPU_ARM_ARCH=${GGML_CPU_ARM_ARCH}")

add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)

add_library(vision_local SHARED vlm_jni.cpp)
target_include_directories(vision_local PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${LLAMA_DIR}/tools/mtmd)
target_link_libraries(vision_local PRIVATE mtmd llama ggml android log)
target_compile_options(vision_local PRIVATE -fvisibility=hidden)
# Only the JNI entry points are exported; 16 KB pages (Android 15+).
target_link_options(vision_local PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
