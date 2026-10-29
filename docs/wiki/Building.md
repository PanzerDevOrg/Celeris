# Building

This page is for people who want to build Celeris from source.

## What you need

- A JDK 25 to run Gradle. Fabric Loom needs it. The build downloads Java 21 by itself for the 1.21.x targets.
- The shared build logic, in a folder named `bichal-build-logic`, next to the Celeris folder.
- CMake and a C++ compiler, only if you want to build the native libraries.

## Steps

1. Clone the shared build logic and Celeris, side by side:

```bash
git clone https://github.com/bichal/BichalBuildLogic.git bichal-build-logic
git clone https://github.com/bichal/Celeris.git
cd Celeris
```

The folder must be called `bichal-build-logic`. If it is missing, the build stops at once and tells you where to clone it.

2. Optional: to build exactly what CI builds, check out the shared build logic at the commit that CI uses. You can find the commit in `.github/workflows/ci.yml`.

3. Build:

```bash
./gradlew build                      # every Minecraft version, with tests
./gradlew :1.21.1:runClient          # a test game for one version, on NeoForge
./gradlew :1.21.1-fabric:runClient   # the same, on Fabric
./gradlew buildAndCollect            # release jars, in build/libs/
```

Other useful tasks:

```bash
./gradlew publishToMavenLocal        # install your build locally, for other mods on this computer
./gradlew benchmarkTest              # ring buffer speed test, not part of build or check
```

## Build one loader only

Add `-Pbichal.loaders=neoforge` or `-Pbichal.loaders=fabric` to build only one loader. For example:

```bash
./gradlew build -Pbichal.loaders=fabric
```

## Native libraries

A local build works without the native libraries. Without them, Celeris uses deflate and the Java physics kernel.

To build the native libraries, add `-Pbichal.native.build=true` to any build, or run one of these tasks:

```bash
./gradlew buildNativeZstd
./gradlew buildNativeCelerisPhysics
```

The native libraries are never stored in the repository. They are build output, written to `natives/` on your computer or by CI. The physics kernel is built so that its math gives the same results as the Java version. A test checks this on every processor type.

Back to [Home](Home).
