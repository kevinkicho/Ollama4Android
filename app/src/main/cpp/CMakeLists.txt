cmake_minimum_required(VERSION 3.31.6)
project("ollama-android" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Path to llama.cpp source (cloned as a subdirectory)
set(LLAMA_CPP_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)

# Build-time check
if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_CPP_DIR}\n"
        "Please clone it:\n"
        "  git clone https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp"
    )
endif()

# llama.cpp build options for Android
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)

# ── CPU Optimizations ───────────────────────────────────────────────────────

if(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "arm64-v8a")
    # NEON SIMD — vectorized math on ARM64
    set(GGML_NEON ON CACHE BOOL "" FORCE)

    # ARM architecture with dotprod + fp16 extensions
    # Snapdragon 8 Gen 1 (S22 Ultra) and 865+ (Tab S7) both support these
    # Set via GGML_CPU_ARM_ARCH so KleidiAI detects +dotprod for its kernels
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

    # OpenMP — parallel processing across CPU cores
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)

    # KleidiAI — ARM's optimized ML kernels for quantized inference
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

    # Optimization flags for speed (-ffast-math minus -ffinite-math-only which llama.cpp forbids)
    add_compile_options(-O3 -funsafe-math-optimizations -fno-finite-math-only)

elseif(${CMAKE_ANDROID_ARCH_ABI} STREQUAL "armeabi-v7a")
    add_compile_options(-mfpu=neon-vfpv4 -O3 -funsafe-math-optimizations -fno-finite-math-only)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

# ── Vulkan GPU Acceleration ────────────────────────────────────────────────
# Adreno 730 (S22 Ultra) and Adreno 650 (Tab S7) both support Vulkan 1.1+
# DISABLED: Vulkan backend causes SIGSEGV in ggml_backend_sched_reserve
# during context creation on Adreno 730, even with 0 GPU layers.
# Likely a Vulkan driver compatibility issue with llama.cpp's backend scheduler.
# To re-enable, uncomment below and set nGpuLayers > 0 in ChatViewModel.kt:
#   set(GGML_VULKAN ON CACHE BOOL "" FORCE)
#   set(ENV{VULKAN_SDK} "C:/VulkanSDK/1.4.341.1")
#   include_directories("C:/VulkanSDK/1.4.341.1/Include")
#   set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${CMAKE_SOURCE_DIR}/host-toolchain.cmake CACHE STRING "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# Add llama.cpp as subdirectory
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

# Our JNI bridge library
add_library(ollama-jni SHARED
    ollama_jni.cpp
)

target_include_directories(ollama-jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
    ${LLAMA_CPP_DIR}/common
)

target_link_libraries(ollama-jni
    llama
    ggml
    common
    android
    log
)
