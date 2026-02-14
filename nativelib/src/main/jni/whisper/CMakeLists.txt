cmake_minimum_required(VERSION 3.10)
project(whisper_jni LANGUAGES C CXX)

# =====================================================================
#  IshizukiTech LLC — Whisper.cpp JNI (Android / CPU-only)
#  ---------------------------------------------------------------------
#  File: CMakeLists.txt
#  Author: Shu Ishizuki
#  License: MIT License
#  © 2026 IshizukiTech LLC. All rights reserved.
#
#  Summary:
#  ---------------------------------------------------------------------
#  - Builds ggml-org/whisper.cpp as Android JNI shared library (CPU-only).
#  - Avoids link errors caused by "GGML_USE_CUDA=0" style defines.
#    (ggml uses #ifdef, so "0" still enables code -> undefined symbols)
#  - Excludes non-Android backends (CUDA/Vulkan/Metal/OpenCL/SYCL/BLAS/etc).
#  - Supports arm64-v8a and armeabi-v7a.
# =====================================================================

# ---------------------------------------------------------------------
# Compiler setup
# ---------------------------------------------------------------------
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------
# Options / cache variables (consume Gradle -D... to avoid "unused variable" warnings)
# ---------------------------------------------------------------------
option(WHISPER_ANDROID_AUTO_CLONE "Auto-clone whisper.cpp when missing" OFF)
option(WHISPER_ANDROID_VERBOSE "Verbose configure/build messages" ON)
option(WHISPER_ENABLE_LTO "Enable LTO on non-Debug builds" ON)

# Compatibility options (often passed by Gradle). This project is CPU-only.
option(GGML_CUDA "Compatibility: GGML CUDA backend (CPU-only build forces OFF)" OFF)
option(GGML_METAL "Compatibility: GGML Metal backend (CPU-only build forces OFF)" OFF)
option(GGML_OPENCL "Compatibility: GGML OpenCL backend (CPU-only build forces OFF)" OFF)
option(GGML_VULKAN "Compatibility: GGML Vulkan backend (CPU-only build forces OFF)" OFF)

# Some setups pass WHISPER_EXTRA. Treat as a compatibility flag.
option(WHISPER_EXTRA "Compatibility: extra whisper build features (no-op here)" OFF)
set(WHISPER_EXTRA_LIBS "" CACHE STRING "Extra link libs (semicolon-separated). Example: foo;bar")

# Optional explicit override for whisper.cpp repository location.
set(WHISPER_DIR "" CACHE PATH "Path to whisper.cpp repo (overrides auto-detection)")

# Optional compatibility variable (some builds pass GGML_HOME).
set(GGML_HOME "" CACHE PATH "Compatibility: GGML_HOME (unused unless you wire it)")

if (WHISPER_ANDROID_VERBOSE)
    set(CMAKE_VERBOSE_MAKEFILE ON)
endif()

# Force-off GPU backend options if someone tries to enable them.
if (GGML_CUDA OR GGML_METAL OR GGML_OPENCL OR GGML_VULKAN)
    message(WARNING "GPU backend options were requested, but this build is CPU-only. Forcing OFF.")
    set(GGML_CUDA   OFF CACHE BOOL "" FORCE)
    set(GGML_METAL  OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------
# Root path auto-detection
# Expected location:
#   nativelib/src/main/jni/whisper/CMakeLists.txt
# ---------------------------------------------------------------------
get_filename_component(ROOT_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)

# ---------------------------------------------------------------------
# Resolve whisper.cpp repo directory
# ---------------------------------------------------------------------
set(WHISPER_LIB_DIR "")

if (WHISPER_DIR AND EXISTS "${WHISPER_DIR}/src/whisper.cpp")
    set(WHISPER_LIB_DIR "${WHISPER_DIR}")
endif()

if (NOT WHISPER_LIB_DIR)
    set(CANDIDATE_PATHS
            "${ROOT_DIR}/nativelib/whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../whisper.cpp"
            "${ROOT_DIR}/whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../../whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../whisper_core"
    )

    foreach(path IN LISTS CANDIDATE_PATHS)
        if (EXISTS "${path}/src/whisper.cpp")
            set(WHISPER_LIB_DIR "${path}")
            break()
        endif()
    endforeach()
endif()

if (NOT WHISPER_LIB_DIR)
    if (WHISPER_ANDROID_AUTO_CLONE)
        set(AUTO_CLONE_DIR "${ROOT_DIR}/nativelib/whisper.cpp")
        message(WARNING "whisper.cpp not found. Auto-clone is ON. Cloning into: ${AUTO_CLONE_DIR}")

        find_program(GIT_EXE git)
        if (NOT GIT_EXE)
            message(FATAL_ERROR "git not found in PATH. Disable auto-clone or provide whisper.cpp as a submodule.")
        endif()

        execute_process(
                COMMAND "${GIT_EXE}" clone --depth=1 https://github.com/ggml-org/whisper.cpp.git "${AUTO_CLONE_DIR}"
                WORKING_DIRECTORY "${ROOT_DIR}"
                RESULT_VARIABLE CLONE_RESULT
        )

        if (CLONE_RESULT EQUAL 0 AND EXISTS "${AUTO_CLONE_DIR}/src/whisper.cpp")
            set(WHISPER_LIB_DIR "${AUTO_CLONE_DIR}")
            message(STATUS "whisper.cpp cloned successfully.")
        else()
            message(FATAL_ERROR "whisper.cpp source not found after clone. Please add it as a git submodule under nativelib/whisper.cpp.")
        endif()
    else()
        message(FATAL_ERROR
                "whisper.cpp source not found.\n"
                "Expected submodule at: ${ROOT_DIR}/nativelib/whisper.cpp\n"
                "Fix: git submodule update --init --recursive\n"
                "Or pass -DWHISPER_DIR=/path/to/whisper.cpp\n"
        )
    endif()
endif()

message(STATUS "Using whisper.cpp directory: ${WHISPER_LIB_DIR}")

# ---------------------------------------------------------------------
# Sources
# ---------------------------------------------------------------------
file(GLOB WHISPER_SRC
        "${WHISPER_LIB_DIR}/src/*.c"
        "${WHISPER_LIB_DIR}/src/*.cpp"
)

# Exclude standalone tools if they slip in
list(FILTER WHISPER_SRC EXCLUDE REGEX ".*/main\\.c$")
list(FILTER WHISPER_SRC EXCLUDE REGEX ".*/main\\.cpp$")

# ---------------------------------------------------------------------
# JNI source (case-insensitive safe selection)
# ---------------------------------------------------------------------
set(JNI_SRC "")
set(JNI_SRC_CANDIDATES
        "${CMAKE_CURRENT_LIST_DIR}/WhisperLib.c"
        "${CMAKE_CURRENT_LIST_DIR}/whisperLib.c"
)

foreach(candidate IN LISTS JNI_SRC_CANDIDATES)
    if (EXISTS "${candidate}")
        set(JNI_SRC "${candidate}")
        break()
    endif()
endforeach()

if (NOT JNI_SRC)
    message(FATAL_ERROR
            "Missing JNI source. Tried:\n"
            " - ${CMAKE_CURRENT_LIST_DIR}/WhisperLib.c\n"
            " - ${CMAKE_CURRENT_LIST_DIR}/whisperLib.c\n"
    )
endif()

message(STATUS "Using JNI source: ${JNI_SRC}")

# GGML core
file(GLOB GGML_CORE_SRC
        "${WHISPER_LIB_DIR}/ggml/src/ggml*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml*.cpp"
)

# Exclude non-Android backends by path/name
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-cann.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-cuda.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-hip.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-metal.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-musa.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-opencl.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-rpc.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-sycl.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-vulkan.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-webgpu.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-blas.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-zendnn.*")
list(FILTER GGML_CORE_SRC EXCLUDE REGEX ".*/ggml-zdnn.*")

# CPU backend
file(GLOB GGML_CPU_SRC
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.cpp"
)

file(GLOB_RECURSE GGML_CPU_ARCH_SRC
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/arch/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/arch/*.cpp"
)

# Exclude non-ARM arch
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/x86/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/powerpc/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/s390/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/loongarch/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/riscv/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/wasm/.*")

# Quants
file(GLOB GGML_QUANTS_SRC
        "${WHISPER_LIB_DIR}/ggml/src/ggml-quants*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-quants*.cpp"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-quants/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-quants/*.cpp"
)

set(SOURCE_FILES
        ${WHISPER_SRC}
        ${JNI_SRC}
        ${GGML_CORE_SRC}
        ${GGML_CPU_SRC}
        ${GGML_CPU_ARCH_SRC}
        ${GGML_QUANTS_SRC}
)

if (WHISPER_ANDROID_VERBOSE)
    list(LENGTH WHISPER_SRC WHISPER_SRC_COUNT)
    list(LENGTH GGML_CORE_SRC GGML_CORE_SRC_COUNT)
    list(LENGTH GGML_CPU_SRC GGML_CPU_SRC_COUNT)
    list(LENGTH GGML_CPU_ARCH_SRC GGML_CPU_ARCH_SRC_COUNT)
    list(LENGTH GGML_QUANTS_SRC GGML_QUANTS_SRC_COUNT)
    list(LENGTH SOURCE_FILES SOURCE_FILES_COUNT)

    message(STATUS "Source counts:")
    message(STATUS "  WHISPER_SRC        = ${WHISPER_SRC_COUNT}")
    message(STATUS "  GGML_CORE_SRC      = ${GGML_CORE_SRC_COUNT}")
    message(STATUS "  GGML_CPU_SRC       = ${GGML_CPU_SRC_COUNT}")
    message(STATUS "  GGML_CPU_ARCH_SRC  = ${GGML_CPU_ARCH_SRC_COUNT}")
    message(STATUS "  GGML_QUANTS_SRC    = ${GGML_QUANTS_SRC_COUNT}")
    message(STATUS "  TOTAL              = ${SOURCE_FILES_COUNT}")
endif()

# ---------------------------------------------------------------------
# Include paths
# ---------------------------------------------------------------------
add_library(ggml_interface INTERFACE)
target_include_directories(ggml_interface INTERFACE
        "${WHISPER_LIB_DIR}"
        "${WHISPER_LIB_DIR}/src"
        "${WHISPER_LIB_DIR}/include"
        "${WHISPER_LIB_DIR}/ggml/include"
        "${WHISPER_LIB_DIR}/ggml/src"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
)

# ---------------------------------------------------------------------
# Version macros
# ---------------------------------------------------------------------
set(LOCAL_GIT_COMMIT "local-build")

find_program(GIT_EXE git)
if (GIT_EXE)
    execute_process(
            COMMAND "${GIT_EXE}" -C "${WHISPER_LIB_DIR}" rev-parse --short HEAD
            OUTPUT_VARIABLE LOCAL_GIT_COMMIT
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
    )
endif()

# ---------------------------------------------------------------------
# Android system libs
# ---------------------------------------------------------------------
find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
find_library(ATOMIC_LIB atomic)
find_library(M_LIB m)

# ---------------------------------------------------------------------
# Build function
# ---------------------------------------------------------------------
function(build_library target_name)
    message(STATUS "Building target: ${target_name}")

    add_library(${target_name} SHARED ${SOURCE_FILES})

    # IMPORTANT:
    # ggml uses #ifdef checks for many backends.
    # Do NOT define GGML_USE_CUDA=0 etc (that still enables code).
    target_compile_definitions(${target_name} PUBLIC
            GGML_USE_CPU=1
            GGML_USE_K_QUANTS=1
            GGML_USE_QKK=1
            GGML_COMPUTE_QK_F32=1

            GGML_VERSION=\"ggml-android\"
            GGML_COMMIT=\"${LOCAL_GIT_COMMIT}\"
            WHISPER_VERSION=\"whisper-android\"
    )

    # Hard-undef backend macros in case something upstream defines them.
    target_compile_options(${target_name} PRIVATE
            -UGGML_USE_CUDA
            -UGGML_USE_HIP
            -UGGML_USE_METAL
            -UGGML_USE_VULKAN
            -UGGML_USE_OPENCL
            -UGGML_USE_SYCL
            -UGGML_USE_BLAS
            -UGGML_USE_CLBLAST
            -UGGML_USE_CANN
            -UGGML_USE_RPC
            -UGGML_USE_WEBGPU
            -UGGML_USE_ZENDNN
            -UGGML_ZENDNN

            -fexceptions
            -frtti
            -Wall
            -Wextra
            -Wno-unused-parameter
            -Wno-unused-function
    )

    # ABI-specific tuning
    if (target_name STREQUAL "whisper_v8fp16_va")
        # NOTE: This enables ARMv8.2 FP16 instructions.
        # Make sure your runtime loader only loads this on compatible devices.
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (target_name STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4 -mfloat-abi=softfp)
    endif()

    # Build type tuning
    if (CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O0 -g -DDEBUG)
    else()
        target_compile_options(${target_name} PRIVATE
                -O3
                -fvisibility=hidden
                -ffunction-sections
                -fdata-sections
        )

        if (WHISPER_ENABLE_LTO)
            target_compile_options(${target_name} PRIVATE -flto)
            target_link_options(${target_name} PRIVATE -flto)
        endif()

        target_link_options(${target_name} PRIVATE
                -Wl,--gc-sections
                -Wl,--exclude-libs,ALL
        )
    endif()

    target_link_libraries(${target_name} PRIVATE ${LOG_LIB} ${ANDROID_LIB} ggml_interface)

    if (ATOMIC_LIB)
        target_link_libraries(${target_name} PRIVATE ${ATOMIC_LIB})
    endif()
    if (M_LIB)
        target_link_libraries(${target_name} PRIVATE ${M_LIB})
    endif()

    # Optional extra libs
    if (WHISPER_EXTRA_LIBS)
        target_link_libraries(${target_name} PRIVATE ${WHISPER_EXTRA_LIBS})
    endif()
endfunction()

# ---------------------------------------------------------------------
# ABI selection
# ---------------------------------------------------------------------
if (DEFINED ANDROID_ABI)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        build_library("whisper_v8fp16_va")
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        build_library("whisper_vfpv4")
    else()
        message(WARNING "Unknown ABI: ${ANDROID_ABI}. Building generic target.")
        build_library("whisper_generic")
    endif()
else()
    build_library("whisper_generic")
endif()

# ---------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------
message(STATUS "whisper.cpp JNI configuration complete.")
message(STATUS "Build type : ${CMAKE_BUILD_TYPE}")
message(STATUS "ABI target : ${ANDROID_ABI}")
message(STATUS "whisper dir: ${WHISPER_LIB_DIR}")
message(STATUS "Commit     : ${LOCAL_GIT_COMMIT}")

if (WHISPER_ANDROID_VERBOSE)
    message(STATUS "Compatibility flags (forced CPU-only):")
    message(STATUS "  GGML_CUDA   = ${GGML_CUDA}")
    message(STATUS "  GGML_METAL  = ${GGML_METAL}")
    message(STATUS "  GGML_OPENCL = ${GGML_OPENCL}")
    message(STATUS "  GGML_VULKAN = ${GGML_VULKAN}")
    message(STATUS "  WHISPER_EXTRA      = ${WHISPER_EXTRA}")
    message(STATUS "  WHISPER_EXTRA_LIBS = ${WHISPER_EXTRA_LIBS}")
    message(STATUS "  GGML_HOME          = ${GGML_HOME}")
    message(STATUS "  WHISPER_DIR        = ${WHISPER_DIR}")
    message(STATUS "  WHISPER_ENABLE_LTO = ${WHISPER_ENABLE_LTO}")
endif()
