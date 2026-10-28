# Building

Clone the shared build logic next to Celeris; the build reads it from `../bichal-build-logic`:

```bash
git clone https://github.com/bichal/BichalBuildLogic.git bichal-build-logic
git clone https://github.com/bichal/Celeris.git
cd Celeris
```

The folder must be called `bichal-build-logic` and sit next to `Celeris` (hence the explicit name in the first clone).
Without it the build stops at once with `bichal-build-logic not found at <path>` and the clone command to run.
To reproduce exactly what CI built for a commit, check bichal-build-logic out at the commit pinned in
[`.github/workflows/ci.yml`](./.github/workflows/ci.yml) (`mod-ci.yml@<sha>`):

```bash
git -C ../bichal-build-logic checkout "$(grep -oE 'mod-ci.yml@[0-9a-f]{40}' .github/workflows/ci.yml | cut -d@ -f2)"
```

Requirements: JDK 25 to run Gradle (Fabric Loom needs it); JDK 21 for the 1.21.x targets is downloaded automatically
through Gradle toolchains. `-Pbichal.loaders=neoforge` (or `fabric`) builds one loader only.

```bash
./gradlew build                     # every Minecraft version, with tests
./gradlew :1.21.1:runClient         # dev client for one version (NeoForge)
./gradlew :1.21.1-fabric:runClient  # the same on Fabric
./gradlew publishToMavenLocal       # use your local build from other mods (never implied by build)
./gradlew benchmarkTest             # ring buffer throughput comparison (not part of build/check)
./gradlew buildAndCollect           # release jars (universal, per-system, sources) in build/libs/<version>/
```

Both native libraries are built with CMake: zstd 1.5.7 from its official release tarball ([`native/zstd`](./native/zstd),
`./gradlew buildNativeZstd`) and the physics kernel from [`native/`](./native) (`./gradlew buildNativeCelerisPhysics`),
or both with `-Pbichal.native.build=true` on any build. CI builds them for every platform and bundles those builds
into the released jars; a local build without them uses deflate and the Java physics kernel.

**Native libraries are never committed.** `natives/<os>/<arch>/` is build output (ignored by git): CMake writes it
locally with `-Pbichal.native.build=true`, and CI writes it from the per-platform native jobs before packaging, so a
released jar always carries libraries built from the sources of the same commit; nothing prebuilt in the repository
can drift from them. The kernel is built with `-ffp-contract=off` and without `-ffast-math` (IEEE parity with the
Java reference, checked by `PhysicsParityTest` on every ISA), plus `-fstack-protector-strong` and, on Linux, full
RELRO (`-z relro -z now`), which change no arithmetic.

[Back to Home](Home.md)
