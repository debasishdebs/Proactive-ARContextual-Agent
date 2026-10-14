cmake_minimum_required(VERSION 3.22.1)
project(llama_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp (git submodule, pinned tag): CPU only, static, nothing but the libraries.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
foreach(gpu GGML_VULKAN GGML_OPENCL GGML_CUDA GGML_METAL GGML_HIP GGML_SYCL GGML_WEBGPU GGML_HEXAGON GGML_KOMPUTE GGML_RPC)
    set(${gpu} OFF CACHE BOOL "" FORCE)
endforeach()

add_subdirectory(llama.cpp llama-build EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni PRIVATE llama ggml)
target_compile_options(llama_jni PRIVATE -fvisibility=hidden)
# Only the JNI entry points are exported; 16 KB pages (Android 15+), the NDK r28 default, made explicit.
target_link_options(llama_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
