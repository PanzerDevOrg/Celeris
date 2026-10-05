![Celeris](./docs/media/banner.png)

**A performance library for NeoForge mods.** Celeris gives mod developers off-heap memory, lock-free concurrency,
SIMD math, fast compression and a batch physics engine, each with a pure-Java fallback, so everything works on any
Java 21+ install with **no launch flags**.

> 📚 **This is a library.** It adds nothing to the game by itself. You only need it when another mod requires it, for
> example [Tessera](https://github.com/PanzerDevOrg/Tessera).

---

## ✨ What's inside

| Feature | What it does |
|---|---|
| 🧠 **Off-heap memory** | FFM on Java 22+, `Unsafe` on Java 21, heap fallback, chosen automatically |
| ⚡ **Lock-free queues** | `MpscRingBuffer` and `AsyncResultQueue` move results from worker threads to the game thread |
| 🧮 **SIMD math** | `jdk.incubator.vector` when enabled, identical scalar results otherwise |
| 📦 **Compression** | zstd and deflate codecs, plus compressed network payloads |
| 🕸️ **Graphs & networks** | Segmented (pipes, wires) and discrete (signals) graphs with pipeline solvers |
| 🏃 **Batch physics** | Thousands of entity bodies per tick on a native SIMD kernel (AVX2/AVX-512/NEON) or a pure-Java one, with vanilla's movement rules |

## 🛡️ Safe by design

- Every native or off-heap feature falls back to pure Java; Celeris never crashes the game because a backend is
  unavailable.
- Network traffic always uses deflate, so clients and servers on different Java versions always understand each
  other.
- Incoming payloads are size-capped and malformed packets are dropped.

## 📋 Requirements

| | |
|---|---|
| **Minecraft** | 1.21 – 1.21.11 · 26.1 – 26.3 (pick the file for your version) |
| **Loader** | NeoForge |
| **Java** | 21 (Minecraft 1.21.x) · 25 (26.x) |
| **Side** | Client and server |

### Which file?

| Minecraft | File |
|---|---|
| 1.21 – 1.21.6 | `celeris-<version>+1.21.1.jar` |
| 1.21.7 – 1.21.10 | `celeris-<version>+1.21.10.jar` |
| 1.21.11 | `celeris-<version>+1.21.11.jar` |
| 26.1 – 26.3 | `celeris-<version>+26.1.jar` |

The standard file works on every system. GitHub releases also offer per-system files (Windows, Linux, macOS, or pure
Java without native libraries) for anyone who wants exactly what their machine runs.

## ⚡ Maximum performance (optional)

Celeris works without any setup. These JVM flags unlock its fastest code paths:

| Minecraft (Java) | Add to your JVM arguments | Unlocks |
|---|---|---|
| 1.21.x (Java 21) | `--enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | Off-heap FFM, native zstd, SIMD, native physics |
| 26.x and up (Java 25+) | `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | SIMD (FFM, zstd and native physics are already on) |

Where to put them:

- **Modrinth App**: instance → *Settings → Java and memory → Java arguments*
- **CurseForge App**: *Settings → Minecraft → Additional arguments*
- **Prism Launcher**: instance → *Settings → Java → JVM arguments*
- **Servers**: `user_jvm_args.txt`

Use `--enable-preview` only with the Java 21 that Minecraft 1.21.x ships with. Without the flags nothing breaks; each
feature uses its pure-Java fallback.

## 👩‍💻 For developers

```kotlin
repositories {
    maven("https://panzerdevorg.github.io/Celeris/maven") {
        content { includeGroup("com.panzer.mods") }
    }
}
dependencies {
    // one artifact per Minecraft version: celeris-<mc version>
    implementation("com.panzer.mods:celeris-1.21.1:0.2.0")
}
```

Docs, examples and source code: **[github.com/PanzerDevOrg/Celeris](https://github.com/PanzerDevOrg/Celeris)**

<!-- publish:off -->

## Package overview

| Package | Contents |
|---|---|
| `core.memory.backend` | `MemoryBackend` with automatic selection: **FFM** → **Unsafe** → **heap** |
| `core.memory` | `PacketPipeline`, `MemoryBus`, zstd (native) / deflate compression codecs |
| `core.concurrency` | `MpscRingBuffer`: lock-free multi-producer/single-consumer ring on off-heap memory |
| `framework.async` | `AsyncResultQueue`: hand results from worker threads to the game/render thread |
| `framework.ticking` | `IdleTickFilter`: skip work while nothing is pending |
| `framework.capability/query` | Capability caching and throttled queries |
| `api.simd` | `VectorOperations`: SIMD via `jdk.incubator.vector`, scalar fallback with identical results |
| `core.topology`, `graph.*` | Network topology plus segmented (pipes, wires) and discrete (per-node signal) graphs |
| `pipeline.*` | Event-driven and continuous (SIMD) pipeline solvers |
| `network` | `PayloadCompression` / `CompressedPayload`: compressed custom payloads |
| `physics` | Batch physics engine: SoA bodies, native C++ kernel or pure-Java kernel, broadphase, entity bridge ([`native/`](./native)) |

Everything is chosen at startup and logged once. `-Dceleris.compatMode=true` forces the pure-Java paths.

## Using Celeris in your mod

Declare it as a required dependency in your `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "celeris"
type = "required"
versionRange = "[0.2.0,)"
ordering = "AFTER"
side = "BOTH"
```

Quick example: offloading work and applying the results on the main thread.

```java
AsyncResultQueue<Result> results = new AsyncResultQueue<>(256); // capacity: power of two
// worker thread
results.offer(computeResult());
// game/render thread, once per tick
results.drainTo(this::apply, 32);
```

More complete, runnable examples (pipes, wires, ring buffer, compressor, memory bank) live in
[`example/`](./example).

## Building

Clone the shared build logic next to Celeris; the build reads it from `../panzer-build-logic`:

```bash
git clone https://github.com/PanzerDevOrg/PanzerBuildLogic.git panzer-build-logic
git clone https://github.com/PanzerDevOrg/Celeris.git
cd Celeris
```

Requirements: JDK 21. JDK 25, for the 26.1 target, is downloaded automatically through Gradle toolchains.

```bash
./gradlew build                     # every Minecraft version, with tests
./gradlew :1.21.1:runClient         # dev client for one version
./gradlew publishToMavenLocal       # use your local build from other mods
./gradlew buildAndCollect           # release jars (universal, per-system, sources) in build/libs/<version>/
```

Native zstd libraries for every supported platform are committed under `natives/<os>/<arch>/` and bundled into the
jar. The physics kernel is built from [`native/`](./native) with CMake (`./gradlew buildNativeCelerisPhysics`, or
`-Ppanzer.native.build=true` on any build); CI builds it for every platform.

## Releasing

This README is the description on Modrinth and CurseForge too (everything outside `publish:off` blocks); see
[`docs/README.md`](./docs/README.md). To release: add `docs/changelogs/<version>.md`, set `version` under `[mod]` in
`mod.stonecutter.properties.toml`, commit, then push a matching tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

CI publishes the Maven repository, a GitHub release with every jar, and the Modrinth and CurseForge files. Every other
push runs the same release as a dry run and uploads a `release-preview` artifact with the pages as they would look.

<!-- panzer:license -->
## License

| Content | License |
|---|---|
| Source code | [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html), see [`LICENSE-AGPL`](./LICENSE-AGPL) |
| Artwork, branding and documentation | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/), see [`LICENSE-CC`](./LICENSE-CC) |
| Zstandard (zstd) (bundled) | [BSD-3-Clause](https://github.com/facebook/zstd), see [`NOTICE`](./NOTICE) |

**Source code:** you may study, modify and redistribute it under the AGPL; if you run a modified version as a network service, its source must be available to that service's users.

**Artwork:** credit Panzer, no commercial use without permission, and share derivatives under the same license.

See [`LICENSE`](./LICENSE) for the full summary.
<!-- /panzer:license -->

<!-- publish:on -->

---

<!-- panzer:footer -->
Code: **AGPL-3.0** · Art: **CC BY-NC-SA 4.0** · Made by **Panzer**
<!-- /panzer:footer -->
