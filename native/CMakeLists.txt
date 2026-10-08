# celeris_physics: batch body simulation kernel, loaded by
# NativePhysicsKernel through the FFM Linker.
#
# One shared library per platform, containing every ISA variant for that
# architecture; cp_api.cpp picks one at load time from CPUID (x86) or uses
# NEON (AArch64). Only cp_isa_<isa>.cpp files get ISA flags, so the library
# loads and runs on any CPU of the architecture.
#
#   cmake -S native -B build-native/linux-x86_64 -DCMAKE_BUILD_TYPE=Release
#   cmake --build build-native/linux-x86_64
#
# Gradle drives this through the `buildPhysicsNative` task (see build.gradle.kts).
cmake_minimum_required(VERSION 3.16)
project(celeris_physics LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(CP_ARCH x86_64)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    set(CP_ARCH aarch64)
else()
    set(CP_ARCH generic)
endif()

set(CP_SOURCES src/cp_api.cpp src/cp_broadphase.cpp src/cp_isa_generic.cpp)
set(CP_DEFINES)

if(CP_ARCH STREQUAL "x86_64")
    list(APPEND CP_SOURCES src/cp_isa_avx2.cpp src/cp_isa_avx512.cpp)
    list(APPEND CP_DEFINES CP_HAVE_AVX2 CP_HAVE_AVX512)
    if(MSVC)
        set_source_files_properties(src/cp_isa_avx2.cpp PROPERTIES COMPILE_OPTIONS "/arch:AVX2")
        set_source_files_properties(src/cp_isa_avx512.cpp PROPERTIES COMPILE_OPTIONS "/arch:AVX512")
    else()
        set_source_files_properties(src/cp_isa_avx2.cpp PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
        set_source_files_properties(src/cp_isa_avx512.cpp PROPERTIES
                COMPILE_OPTIONS "-mavx2;-mfma;-mavx512f;-mavx512dq;-mavx512vl")
    endif()
elseif(CP_ARCH STREQUAL "aarch64")
    # Advanced SIMD (incl. f64 FMA) is mandatory in AArch64: no flags, no dispatch.
    list(APPEND CP_SOURCES src/cp_isa_neon.cpp)
    list(APPEND CP_DEFINES CP_HAVE_NEON)
endif()

add_library(celeris_physics SHARED ${CP_SOURCES})
target_include_directories(celeris_physics PRIVATE include)
target_compile_definitions(celeris_physics PRIVATE ${CP_DEFINES} $<$<CONFIG:Release>:NDEBUG>)

if(MSVC)
    # /fp:precise (default) keeps a*b+c as two roundings; never /fp:fast here:
    # vanilla parity needs IEEE results identical to the Java reference.
    # /GS (stack canaries) is on by default and stays on.
    target_compile_options(celeris_physics PRIVATE /O2 /GR- /fp:precise)
    string(REGEX REPLACE "/EH[sc]+" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    target_compile_options(celeris_physics PRIVATE /EHs-c-)
else()
    # -ffp-contract=off: no implicit FMA contraction (Clang contracts by default).
    # No -ffast-math: it breaks NaN/inf handling and reassociates, both of
    # which would make native and Java results diverge.
    target_compile_options(celeris_physics PRIVATE
            -O3 -ffp-contract=off -fno-math-errno -fno-exceptions -fno-rtti -fomit-frame-pointer
            -Wall -Wextra)
    # Hardening that changes no arithmetic: a stack canary in functions with
    # local arrays (only the per-batch step entry points get one; GCC on
    # Ubuntu already did this by default) and, on ELF, a read-only GOT bound
    # at load (full RELRO). Same generated code otherwise; parity tests and
    # the physics benchmark show no difference.
    if(NOT MINGW)
        target_compile_options(celeris_physics PRIVATE -fstack-protector-strong)
    endif()
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        # The .so must not depend on the player's libstdc++ version.
        target_link_options(celeris_physics PRIVATE -static-libstdc++ -static-libgcc -Wl,-z,relro,-z,now)
    endif()
    if(MINGW)
        target_link_options(celeris_physics PRIVATE -static)
    endif()
    # No LTO: there are no hot cross-unit calls to gain from, and keeping
    # each ISA unit's code generation isolated is the whole point of the split.
endif()

set_target_properties(celeris_physics PROPERTIES
        OUTPUT_NAME "celeris_physics"
        PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}")

# Standalone C++ self-test (no JVM): ctest --test-dir <build dir>
option(CP_BUILD_TESTS "Build the native self-test" ON)
if(CP_BUILD_TESTS AND NOT CMAKE_CROSSCOMPILING)
    enable_testing()
    add_executable(cp_selftest test/cp_selftest.cpp)
    target_include_directories(cp_selftest PRIVATE include)
    target_link_libraries(cp_selftest PRIVATE celeris_physics)
    add_test(NAME cp_selftest COMMAND cp_selftest)
    # Hostile inputs (NaN/Inf/huge values, misaligned slab, malformed calls) on
    # every ISA; CI also runs it under ASan/UBSan (.github/workflows/native-sanitizers.yml).
    add_executable(cp_fuzz test/cp_fuzz.cpp)
    target_include_directories(cp_fuzz PRIVATE include)
    target_link_libraries(cp_fuzz PRIVATE celeris_physics)
    add_test(NAME cp_fuzz COMMAND cp_fuzz)
endif()
