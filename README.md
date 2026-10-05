![Celeris](./docs/media/banner.png)

Celeris is the part of a mod you don't see: the memory, threading and math underneath. It runs a few thousand
entity bodies through one SIMD pass per tick, hands worker results to the game thread without a lock, and compresses
what goes over the wire. If a faster backend isn't there, it uses the next one down, so it behaves the same on a
stock launcher as on a tuned one.

> On its own it changes nothing in game. You install it because a mod such as
> [Tessera](https://github.com/PanzerDevOrg/Tessera) asks for it.

---

## Inside

| | |
|---|---|
| **Memory** | FFM on Java 22+, `Unsafe` on 21, plain heap as a last resort |
| **Threads** | `MpscRingBuffer` and `AsyncResultQueue`: workers produce, the tick consumes |
| **Math** | `jdk.incubator.vector` when the module is loaded; the scalar path gives bit-identical results |
| **Compression** | zstd and deflate, and a compressed payload type for custom packets |
| **Graphs** | Segmented networks (pipes, wires) and discrete ones (signals), with pipeline solvers |
| **Physics** | Batch bodies on a native kernel (AVX2, AVX-512, NEON) or in Java, following vanilla's movement rules |

Packets between client and server always use deflate, so a Java 21 client and a Java 25 server never disagree on the
format. Oversized or malformed payloads are dropped, not trusted.

## Versions

| Minecraft | File | Java |
|---|---|---|
| 1.21 – 1.21.6 | `celeris-<version>+1.21.1.jar` | 21 |
| 1.21.7 – 1.21.10 | `celeris-<version>+1.21.10.jar` | 21 |
| 1.21.11 | `celeris-<version>+1.21.11.jar` | 21 |
| 26.1 – 26.3 | `celeris-<version>+26.1.jar` | 25 |

NeoForge, client and server. Each jar has been booted on every Minecraft version in its row. The
GitHub releases also carry per-system builds (Windows, Linux, macOS, or Java only) if you'd rather not ship natives
you won't load.

## Flags

None are required. These open the fast paths that the JVM keeps closed by default:

| Java | JVM arguments | Opens |
|---|---|---|
| 21 (Minecraft 1.21.x) | `--enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | FFM memory, native zstd, SIMD, native physics |
| 25 (Minecraft 26.x) | `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | SIMD (the rest is already open on 25) |

Modrinth App: *Settings → Java and memory*. CurseForge: *Settings → Minecraft → Additional arguments*. Prism: instance
*Settings → Java*. Servers: `user_jvm_args.txt`. `--enable-preview` belongs to Java 21 only.

## For mod authors

```kotlin
repositories {
    maven("https://panzerdevorg.github.io/Celeris/maven") {
        content { includeGroup("com.panzer.mods") }
    }
}
dependencies {
    implementation("com.panzer.mods:celeris-1.21.1:0.2.0") // celeris-<minecraft version>
}
```

Source, examples and the package map: **[github.com/PanzerDevOrg/Celeris](https://github.com/PanzerDevOrg/Celeris)**

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
