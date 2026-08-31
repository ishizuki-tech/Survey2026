cmake_minimum_required(VERSION 3.10)

project(whisper_jni LANGUAGES C CXX)

# =====================================================================
#  IshizukiTech LLC — Whisper.cpp JNI (Android / CPU-only)
#  ---------------------------------------------------------------------
#  File: CMakeLists.txt
#  Author: Shu Ishizuki
#  License: MIT License
#  © 2026 IshizukiTech LLC. All rights reserved.
# =====================================================================
#
#  Design:
#  ---------------------------------------------------------------------
#  - Builds whisper.cpp + GGML into a monolithic JNI shared library.
#  - CPU-only Android build.
#  - Supports arm64-v8a and armeabi-v7a.
#  - Builds an optimized library when appropriate.
#  - Also builds libwhisper.so as a safe runtime fallback.
#  - Avoids defining disabled GGML backends as "...=0" because many
#    upstream checks use #ifdef rather than #if.
#  - Keeps JNI symbol names compatible with:
#      com.whispercpp.whisper.WhisperLib
#
#  Important:
#  ---------------------------------------------------------------------
#  Pin whisper.cpp to a known Git commit/submodule revision in production.
#  This file intentionally uses source discovery for a monolithic library,
#  so upstream source-layout changes should always be validated after an
#  upgrade.
# =====================================================================

# ---------------------------------------------------------------------
# Language / build defaults
# ---------------------------------------------------------------------

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------
# Project options
# ---------------------------------------------------------------------

option(
        WHISPER_ANDROID_AUTO_CLONE
        "Auto-clone whisper.cpp when the source tree is missing"
        OFF
)

option(
        WHISPER_ANDROID_VERBOSE
        "Enable verbose configure/build diagnostics"
        ON
)

option(
        WHISPER_ENABLE_LTO
        "Enable interprocedural optimization for non-Debug builds"
        ON
)

option(
        WHISPER_BUILD_GENERIC_FALLBACK
        "Build generic libwhisper.so in addition to optimized ARM variants"
        ON
)

# Compatibility options sometimes supplied by Gradle.
# This project is CPU-only and always forces them OFF.
option(GGML_CUDA   "Compatibility option; forced OFF" OFF)
option(GGML_METAL  "Compatibility option; forced OFF" OFF)
option(GGML_OPENCL "Compatibility option; forced OFF" OFF)
option(GGML_VULKAN "Compatibility option; forced OFF" OFF)
option(GGML_HIP    "Compatibility option; forced OFF" OFF)
option(GGML_SYCL   "Compatibility option; forced OFF" OFF)
option(GGML_BLAS   "Compatibility option; forced OFF" OFF)
option(GGML_RPC    "Compatibility option; forced OFF" OFF)

# Optional extra native libraries.
set(
        WHISPER_EXTRA_LIBS
        ""
        CACHE STRING
        "Extra native link libraries separated by semicolons"
)

# Optional explicit whisper.cpp checkout.
set(
        WHISPER_DIR
        ""
        CACHE PATH
        "Path to whisper.cpp repository"
)

if (WHISPER_ANDROID_VERBOSE)
    set(CMAKE_VERBOSE_MAKEFILE ON)
endif()

# ---------------------------------------------------------------------
# Force non-CPU backends OFF
# ---------------------------------------------------------------------

foreach(backend
        GGML_CUDA
        GGML_METAL
        GGML_OPENCL
        GGML_VULKAN
        GGML_HIP
        GGML_SYCL
        GGML_BLAS
        GGML_RPC
)
    if (${backend})
        message(
                WARNING
                "${backend} was requested, but this JNI build is CPU-only. Forcing OFF."
        )
    endif()

    set(${backend} OFF CACHE BOOL "" FORCE)
endforeach()

# ---------------------------------------------------------------------
# Resolve project root
#
# Expected CMake location:
#   nativelib/src/main/jni/whisper/CMakeLists.txt
# ---------------------------------------------------------------------

get_filename_component(
        ROOT_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../.."
        ABSOLUTE
)

# ---------------------------------------------------------------------
# Resolve whisper.cpp source directory
# ---------------------------------------------------------------------

set(WHISPER_LIB_DIR "")

if (
        WHISPER_DIR
        AND EXISTS "${WHISPER_DIR}/src/whisper.cpp"
        AND EXISTS "${WHISPER_DIR}/ggml/src"
)
    get_filename_component(
            WHISPER_LIB_DIR
            "${WHISPER_DIR}"
            ABSOLUTE
    )
endif()

if (NOT WHISPER_LIB_DIR)
    set(
            CANDIDATE_PATHS
            "${ROOT_DIR}/nativelib/whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../whisper.cpp"
            "${ROOT_DIR}/whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../../whisper.cpp"
            "${CMAKE_CURRENT_LIST_DIR}/../../../whisper_core"
    )

    foreach(path IN LISTS CANDIDATE_PATHS)
        if (
                EXISTS "${path}/src/whisper.cpp"
                AND EXISTS "${path}/ggml/src"
        )
            get_filename_component(
                    WHISPER_LIB_DIR
                    "${path}"
                    ABSOLUTE
            )
            break()
        endif()
    endforeach()
endif()

if (NOT WHISPER_LIB_DIR)
    if (WHISPER_ANDROID_AUTO_CLONE)
        set(
                AUTO_CLONE_DIR
                "${ROOT_DIR}/nativelib/whisper.cpp"
        )

        message(
                WARNING
                "whisper.cpp not found. Auto-clone is enabled: ${AUTO_CLONE_DIR}"
        )

        find_program(GIT_EXE git)

        if (NOT GIT_EXE)
            message(
                    FATAL_ERROR
                    "git was not found. Install git, disable auto-clone, or provide WHISPER_DIR."
            )
        endif()

        execute_process(
                COMMAND
                "${GIT_EXE}"
                clone
                --depth=1
                https://github.com/ggml-org/whisper.cpp.git
                "${AUTO_CLONE_DIR}"
                WORKING_DIRECTORY "${ROOT_DIR}"
                RESULT_VARIABLE CLONE_RESULT
        )

        if (
                CLONE_RESULT EQUAL 0
                AND EXISTS "${AUTO_CLONE_DIR}/src/whisper.cpp"
        )
            set(
                    WHISPER_LIB_DIR
                    "${AUTO_CLONE_DIR}"
            )
        else()
            message(
                    FATAL_ERROR
                    "whisper.cpp auto-clone failed. Prefer a pinned git submodule for reproducible builds."
            )
        endif()
    else()
        message(
                FATAL_ERROR
                "whisper.cpp source not found.\n"
                "Expected: ${ROOT_DIR}/nativelib/whisper.cpp\n"
                "Run: git submodule update --init --recursive\n"
                "Or pass: -DWHISPER_DIR=/path/to/whisper.cpp"
        )
    endif()
endif()

message(
        STATUS
        "Using whisper.cpp directory: ${WHISPER_LIB_DIR}"
)

# ---------------------------------------------------------------------
# Resolve upstream version / commit
# ---------------------------------------------------------------------

set(WHISPER_UPSTREAM_VERSION "unknown")
set(WHISPER_UPSTREAM_COMMIT "local-build")

if (EXISTS "${WHISPER_LIB_DIR}/CMakeLists.txt")
    file(
            READ
            "${WHISPER_LIB_DIR}/CMakeLists.txt"
            WHISPER_MAIN_CMAKE
    )

    string(
            REGEX MATCH
            "project\\(\"?whisper\\.cpp\"?[ \t]+VERSION[ \t]+([0-9]+\\.[0-9]+\\.[0-9]+)"
            WHISPER_VERSION_MATCH
            "${WHISPER_MAIN_CMAKE}"
    )

    if (CMAKE_MATCH_1)
        set(
                WHISPER_UPSTREAM_VERSION
                "${CMAKE_MATCH_1}"
        )
    endif()

    if (WHISPER_UPSTREAM_VERSION STREQUAL "unknown")
        string(
                REGEX MATCH
                "set\\(WHISPER_VERSION_MAJOR[ \t]+([0-9]+)\\)[ \t\r\n]+set\\(WHISPER_VERSION_MINOR[ \t]+([0-9]+)\\)[ \t\r\n]+set\\(WHISPER_VERSION_PATCH[ \t]+([0-9]+)\\)"
                WHISPER_VERSION_PARTS_MATCH
                "${WHISPER_MAIN_CMAKE}"
        )

        if (WHISPER_VERSION_PARTS_MATCH)
            set(
                    WHISPER_UPSTREAM_VERSION
                    "${CMAKE_MATCH_1}.${CMAKE_MATCH_2}.${CMAKE_MATCH_3}"
            )
        endif()
    endif()
endif()

find_program(GIT_EXE git)

if (GIT_EXE)
    execute_process(
            COMMAND
            "${GIT_EXE}"
            -C
            "${WHISPER_LIB_DIR}"
            rev-parse
            --short
            HEAD
            RESULT_VARIABLE GIT_RESULT
            OUTPUT_VARIABLE GIT_COMMIT_OUTPUT
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
    )

    if (
            GIT_RESULT EQUAL 0
            AND NOT "${GIT_COMMIT_OUTPUT}" STREQUAL ""
    )
        set(
                WHISPER_UPSTREAM_COMMIT
                "${GIT_COMMIT_OUTPUT}"
        )
    endif()
endif()

message(
        STATUS
        "whisper.cpp version: ${WHISPER_UPSTREAM_VERSION}"
)

message(
        STATUS
        "whisper.cpp commit : ${WHISPER_UPSTREAM_COMMIT}"
)

# ---------------------------------------------------------------------
# JNI source
# ---------------------------------------------------------------------

set(JNI_SRC "")

set(
        JNI_SRC_CANDIDATES
        "${CMAKE_CURRENT_LIST_DIR}/WhisperLib.c"
        "${CMAKE_CURRENT_LIST_DIR}/whisperLib.c"
)

foreach(candidate IN LISTS JNI_SRC_CANDIDATES)
    if (EXISTS "${candidate}")
        set(
                JNI_SRC
                "${candidate}"
        )
        break()
    endif()
endforeach()

if (NOT JNI_SRC)
    message(
            FATAL_ERROR
            "JNI source was not found.\n"
            "Tried:\n"
            "  ${CMAKE_CURRENT_LIST_DIR}/WhisperLib.c\n"
            "  ${CMAKE_CURRENT_LIST_DIR}/whisperLib.c"
    )
endif()

message(
        STATUS
        "Using JNI source: ${JNI_SRC}"
)

# ---------------------------------------------------------------------
# Source discovery
#
# CONFIGURE_DEPENDS makes CMake re-check the glob when the upstream tree
# adds/removes files. A pinned whisper.cpp revision is still recommended.
# ---------------------------------------------------------------------

file(
        GLOB
        WHISPER_SRC
        CONFIGURE_DEPENDS
        "${WHISPER_LIB_DIR}/src/*.c"
        "${WHISPER_LIB_DIR}/src/*.cpp"
)

list(
        FILTER
        WHISPER_SRC
        EXCLUDE
        REGEX ".*/main\\.(c|cpp)$"
)

# b4938 adds Parakeet as a separate upstream library. Keep this JNI target
# limited to Whisper instead of absorbing Parakeet through the source glob.
list(FILTER WHISPER_SRC EXCLUDE REGEX ".*/parakeet\\.cpp$")

file(
        GLOB
        GGML_CORE_SRC
        CONFIGURE_DEPENDS
        "${WHISPER_LIB_DIR}/ggml/src/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/*.cpp"
)

# Exclude optional backend translation units from the monolithic CPU build.
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

file(
        GLOB
        GGML_CPU_SRC
        CONFIGURE_DEPENDS
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.cpp"
)

file(
        GLOB_RECURSE
        GGML_CPU_ARCH_SRC
        CONFIGURE_DEPENDS
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/arch/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/arch/*.cpp"
)

# Android targets in this project are ARM-only.
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/x86/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/powerpc/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/s390/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/loongarch/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/riscv/.*")
list(FILTER GGML_CPU_ARCH_SRC EXCLUDE REGEX ".*/arch/wasm/.*")

set(
        SOURCE_FILES
        ${WHISPER_SRC}
        ${JNI_SRC}
        ${GGML_CORE_SRC}
        ${GGML_CPU_SRC}
        ${GGML_CPU_ARCH_SRC}
)

# Protect against overlapping glob patterns in future upstream versions.
list(REMOVE_DUPLICATES SOURCE_FILES)

if (WHISPER_ANDROID_VERBOSE)
    list(LENGTH WHISPER_SRC WHISPER_SRC_COUNT)
    list(LENGTH GGML_CORE_SRC GGML_CORE_SRC_COUNT)
    list(LENGTH GGML_CPU_SRC GGML_CPU_SRC_COUNT)
    list(LENGTH GGML_CPU_ARCH_SRC GGML_CPU_ARCH_SRC_COUNT)
    list(LENGTH SOURCE_FILES SOURCE_FILES_COUNT)

    message(STATUS "Source counts:")
    message(STATUS "  WHISPER_SRC       = ${WHISPER_SRC_COUNT}")
    message(STATUS "  GGML_CORE_SRC     = ${GGML_CORE_SRC_COUNT}")
    message(STATUS "  GGML_CPU_SRC      = ${GGML_CPU_SRC_COUNT}")
    message(STATUS "  GGML_CPU_ARCH_SRC = ${GGML_CPU_ARCH_SRC_COUNT}")
    message(STATUS "  TOTAL             = ${SOURCE_FILES_COUNT}")
endif()

# ---------------------------------------------------------------------
# Android / whisper / ggml include directories
# ---------------------------------------------------------------------

set(
        WHISPER_INCLUDE_DIRS
        "${WHISPER_LIB_DIR}"
        "${WHISPER_LIB_DIR}/src"
        "${WHISPER_LIB_DIR}/include"
        "${WHISPER_LIB_DIR}/ggml/include"
        "${WHISPER_LIB_DIR}/ggml/src"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
)

# ---------------------------------------------------------------------
# Android system libraries
# ---------------------------------------------------------------------

find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
find_library(ATOMIC_LIB atomic)
find_library(M_LIB m)

# ---------------------------------------------------------------------
# Shared-library builder
# ---------------------------------------------------------------------

function(build_whisper_library target_name optimized_variant)

    message(
            STATUS
            "Building target: ${target_name} (${optimized_variant})"
    )

    add_library(
            ${target_name}
            SHARED
            ${SOURCE_FILES}
    )

    target_include_directories(
            ${target_name}
            PRIVATE
            ${WHISPER_INCLUDE_DIRS}
    )

    # Only define the CPU backend as enabled.
    # Do not define disabled backend macros to zero because upstream code
    # frequently checks them with #ifdef.
    target_compile_definitions(
            ${target_name}
            PRIVATE
            _GNU_SOURCE
            GGML_USE_CPU
            WHISPER_VERSION="${WHISPER_UPSTREAM_VERSION}"
            GGML_VERSION="ggml-android"
            GGML_COMMIT="${WHISPER_UPSTREAM_COMMIT}"
    )

    # Defensive undefines in case flags are inherited from Gradle/toolchain.
    target_compile_options(
            ${target_name}
            PRIVATE
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
            -Wall
            -Wextra
            -Wno-unused-parameter
            -Wno-unused-function
    )

    # -------------------------------------------------------------
    # ABI-specific tuning
    # -------------------------------------------------------------

    if (optimized_variant STREQUAL "ARM64_FP16")
        # Requires ARMv8.2-A FP16 arithmetic support at runtime.
        target_compile_options(
                ${target_name}
                PRIVATE
                -march=armv8.2-a+fp16
        )

    elseif (optimized_variant STREQUAL "ARMV7_VFPV4")
        # Let the Android NDK/toolchain select the ABI float calling
        # convention. Only request the desired instruction capability.
        target_compile_options(
                ${target_name}
                PRIVATE
                -mfpu=neon-vfpv4
        )
    endif()

    # -------------------------------------------------------------
    # Build-type tuning
    # -------------------------------------------------------------

    if (CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(
                ${target_name}
                PRIVATE
                -O0
                -g
        )

        target_compile_definitions(
                ${target_name}
                PRIVATE
                DEBUG
        )
    else()
        target_compile_options(
                ${target_name}
                PRIVATE
                -O3
                -fvisibility=hidden
                -fvisibility-inlines-hidden
                -ffunction-sections
                -fdata-sections
        )

        target_link_options(
                ${target_name}
                PRIVATE
                -Wl,--gc-sections
                -Wl,--exclude-libs,ALL
        )

        if (WHISPER_ENABLE_LTO)
            include(CheckIPOSupported)

            check_ipo_supported(
                    RESULT IPO_SUPPORTED
                    OUTPUT IPO_ERROR
            )

            if (IPO_SUPPORTED)
                set_property(
                        TARGET ${target_name}
                        PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE
                )
            else()
                message(
                        WARNING
                        "IPO/LTO is not supported for ${target_name}: ${IPO_ERROR}"
                )
            endif()
        endif()
    endif()

    target_link_libraries(
            ${target_name}
            PRIVATE
            ${LOG_LIB}
            ${ANDROID_LIB}
    )

    if (ATOMIC_LIB)
        target_link_libraries(
                ${target_name}
                PRIVATE
                ${ATOMIC_LIB}
        )
    endif()

    if (M_LIB)
        target_link_libraries(
                ${target_name}
                PRIVATE
                ${M_LIB}
        )
    endif()

    if (WHISPER_EXTRA_LIBS)
        target_link_libraries(
                ${target_name}
                PRIVATE
                ${WHISPER_EXTRA_LIBS}
        )
    endif()

endfunction()

# ---------------------------------------------------------------------
# ABI target selection
#
# Kotlin runtime loader currently tries:
#   arm64 FP16 -> whisper_v8fp16_va -> whisper
#   armv7 VFPv4 -> whisper_vfpv4    -> whisper
#   otherwise   -> whisper
#
# Therefore libwhisper.so must exist whenever generic fallback is enabled.
# ---------------------------------------------------------------------

if (DEFINED ANDROID_ABI)

    if (ANDROID_ABI STREQUAL "arm64-v8a")

        build_whisper_library(
                "whisper_v8fp16_va"
                "ARM64_FP16"
        )

        if (WHISPER_BUILD_GENERIC_FALLBACK)
            build_whisper_library(
                    "whisper"
                    "GENERIC"
            )
        endif()

    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")

        build_whisper_library(
                "whisper_vfpv4"
                "ARMV7_VFPV4"
        )

        if (WHISPER_BUILD_GENERIC_FALLBACK)
            build_whisper_library(
                    "whisper"
                    "GENERIC"
            )
        endif()

    else()

        message(
                WARNING
                "Unsupported/unknown Android ABI '${ANDROID_ABI}'. Building generic libwhisper.so."
        )

        build_whisper_library(
                "whisper"
                "GENERIC"
        )

    endif()

else()

    message(
            WARNING
            "ANDROID_ABI is not defined. Building generic libwhisper.so."
    )

    build_whisper_library(
            "whisper"
            "GENERIC"
    )

endif()

# ---------------------------------------------------------------------
# Configuration summary
# ---------------------------------------------------------------------

message(STATUS "whisper.cpp JNI configuration complete")
message(STATUS "Build type        : ${CMAKE_BUILD_TYPE}")
message(STATUS "Android ABI       : ${ANDROID_ABI}")
message(STATUS "whisper.cpp dir   : ${WHISPER_LIB_DIR}")
message(STATUS "whisper.cpp ver   : ${WHISPER_UPSTREAM_VERSION}")
message(STATUS "whisper.cpp commit: ${WHISPER_UPSTREAM_COMMIT}")
message(STATUS "Generic fallback  : ${WHISPER_BUILD_GENERIC_FALLBACK}")
message(STATUS "LTO requested     : ${WHISPER_ENABLE_LTO}")
