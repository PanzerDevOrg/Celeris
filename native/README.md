# celeris_physics

Native kernel of Celeris's batch physics engine (`com.panzer.mods.celeris.physics`).
It simulates thousands of entity bodies per call over an off-heap Structure-of-Arrays
slab that the Java side owns, and is loaded through the FFM `Linker`.

## Layout

```
native/
├── include/celeris_physics.h   C ABI: column layout, flags, cell classes, entry points
├── src/cp_api.cpp              exports, CPUID/XGETBV detection, ISA dispatch (baseline ISA)
├── src/cp_kernel.inc           the kernel (collision + step), included once per ISA
├── src/cp_isa_generic.cpp      baseline: SSE2 on x86-64, any AArch64
├── src/cp_isa_avx2.cpp         AVX2 + FMA     (-mavx2 -mfma, /arch:AVX2)
├── src/cp_isa_avx512.cpp       AVX-512 F/DQ/VL (opt-in)
├── src/cp_isa_neon.cpp         AArch64 Advanced SIMD
├── src/cp_broadphase.inc       spatial-hash broadphase, compiled per ISA like cp_kernel.inc
└── test/cp_selftest.cpp        scenarios + ISA parity + broadphase vs brute force
```

## Design rules

- **One library per platform, every ISA inside.** Only the `cp_isa_*.cpp` files get
  ISA flags; `cp_api.cpp` picks one at load time, so the library runs on any CPU of
  its architecture and never faults with an illegal instruction.
- **No shared inline code between ISA units.** `cp_kernel.inc` is included inside an
  anonymous namespace per ISA and uses no `std::` templates: an inline function
  emitted by two units built with different `-m` flags is merged by the linker,
  which could hand AVX-512 code to the generic path.
- **IEEE results identical to Java.** `-ffp-contract=off`, no `-ffast-math`,
  `/fp:precise`. `CP_MODE_VANILLA` is bit-identical to the Java reference kernel
  (`JavaPhysicsKernel`) on every ISA; `PhysicsParityTest` and `cp_selftest`
  enforce it. FMA is used only in `CP_MODE_FUSED`, where it is the defined semantics.
- **No allocation, no exceptions, no callbacks into Java.** Every buffer comes from
  the caller, so the entry points can be linked as critical (no thread-state
  transition).
- **AVX2 by default.** The vector passes are a small share of a step; collision is
  gather-bound scalar code. On the CPUs measured, AVX-512's frequency/power
  transitions cost more than its wider lanes win. `-Dceleris.physics.isa=avx512`
  turns it on.

## Building

```bash
cmake -S native -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native --parallel
ctest --test-dir build-native --output-on-failure
```

or through Gradle (panzer-build-logic, `[natives.celeris_physics]` in
`mod.stonecutter.properties.toml`):

```bash
./gradlew buildNativeCelerisPhysics          # host platform -> natives/<os>/<arch>/
./gradlew build -Ppanzer.native.build=true   # rebuild it before packaging
```

CI builds and self-tests it on Linux x86_64/AArch64, Windows x86_64 and macOS
x86_64/AArch64, and bundles all of them in the jar. JVMs without FFM (Minecraft
1.21.x without `--enable-preview`) and platforms without a binary run the
pure-Java kernel (`JavaPhysicsKernel`, on Java arrays) instead, with the same
results.
