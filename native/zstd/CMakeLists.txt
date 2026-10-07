# zstd: Zstandard's official 1.5.7 release, built from source as one shared
# library per platform (libzstd.so / zstd.dll / libzstd.dylib), loaded by
# Celeris's native compression codec. CI builds every platform on its own
# runner from the release tarball below, pinned by its SHA-256 (the same one
# Meta publishes next to it), so the binaries in the jar come from known source.
#
#   cmake -S native/zstd -B build-native/zstd -DCMAKE_BUILD_TYPE=Release
#   cmake --build build-native/zstd --config Release
cmake_minimum_required(VERSION 3.18)
project(celeris_zstd LANGUAGES C)

set(ZSTD_VERSION 1.5.7)
set(ZSTD_URL https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz)
set(ZSTD_SHA256 eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# The library and the CTest check land side by side (out/, or out/<config> with
# multi-config generators), so Windows finds the DLL next to the test.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)

include(FetchContent)
FetchContent_Declare(zstd
        URL ${ZSTD_URL}
        URL_HASH SHA256=${ZSTD_SHA256}
        SOURCE_SUBDIR build/cmake
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
# Only the shared library: no CLI, no tests, no static archive.
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED ON CACHE BOOL "" FORCE)
set(ZSTD_LEGACY_SUPPORT OFF CACHE BOOL "" FORCE)
# Single-threaded: Celeris compresses on its own threads, and without pthread the
# Linux library needs nothing newer than glibc 2.14 (built on any runner).
set(ZSTD_MULTITHREAD_SUPPORT OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(zstd)

# CMake's default names for the CI to collect: libzstd.so (not the versioned
# libzstd.so.1.5.7 symlink chain), zstd.dll, libzstd.dylib.
set_target_properties(libzstd_shared PROPERTIES
        OUTPUT_NAME zstd
        VERSION ""
        SOVERSION "")
if(APPLE)
    # The jar's loader opens it by absolute path; @rpath lets the CTest check find it too.
    set_target_properties(libzstd_shared PROPERTIES INSTALL_NAME_DIR "@rpath" BUILD_WITH_INSTALL_NAME_DIR ON)
elseif(UNIX)
    set_target_properties(libzstd_shared PROPERTIES NO_SONAME ON)
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # Self-contained: no dependency on the player's libgcc.
    target_link_options(libzstd_shared PRIVATE -static-libgcc)
endif()

# Celeris's JNI entry points, in the same library: Java 21 without
# --enable-preview has no FFM, so this is how it reaches zstd there. Plain
# integer arguments only, so no jni.h (and no JDK) is needed to build it.
target_sources(libzstd_shared PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/celeris_zstd_jni.c)
target_include_directories(libzstd_shared PRIVATE ${zstd_SOURCE_DIR}/lib ${CMAKE_CURRENT_SOURCE_DIR})

# Smoke test: the library loads, reports the pinned version, and its JNI entry
# points round-trip through reused contexts.
enable_testing()
add_executable(zstd_version_check version_check.c)
target_link_libraries(zstd_version_check PRIVATE libzstd_shared)
target_include_directories(zstd_version_check PRIVATE ${zstd_SOURCE_DIR}/lib ${CMAKE_CURRENT_SOURCE_DIR})
add_test(NAME zstd_version COMMAND zstd_version_check ${ZSTD_VERSION})
