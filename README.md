# Celeris

**A performance library for NeoForge mods:** off-heap memory, lock-free concurrency, SIMD math, compression and
graph/network utilities, each with a pure-Java fallback so it runs on any JVM without launch flags.

Celeris is a library mod: on its own it adds no gameplay. Other mods (for example
[Tessera](https://github.com/PanzerDevOrg/Tessera)) depend on it.

| Minecraft | 1.21 – 1.21.11 and 26.1 – 26.3 (4 jars, see below)      |
|-----------|-------------------------------------------------------------|
| Loader    | NeoForge                                                    |
| Java      | 21 (Minecraft 1.21.x), 25 (26.x)                            |
| Side      | Client and server                                           |

## What it provides

| Package                      | Contents                                                                                     |
|------------------------------|----------------------------------------------------------------------------------------------|
| `core.memory.backend`        | `MemoryBackend` with automatic selection: **FFM** → **Unsafe** → **heap** (see below)        |
| `core.memory`                | `PacketPipeline`, `MemoryBus`, zstd (native) / deflate compression codecs                    |
| `core.concurrency`           | `MpscRingBuffer`: lock-free multi-producer/single-consumer ring on off-heap memory           |
| `framework.async`            | `AsyncResultQueue`: hand results from worker threads to the game/render thread               |
| `framework.ticking`          | `IdleTickFilter`: skip work while nothing is pending                                         |
| `framework.capability/query` | Capability caching and throttled queries                                                     |
| `api.simd`                   | `VectorOperations`: SIMD via `jdk.incubator.vector`, scalar fallback with identical results |
| `core.topology`, `graph.*`   | Network topology plus segmented (pipes, wires) and discrete (per-node signal) graphs          |
| `pipeline.*`                 | Event-driven and continuous (SIMD) pipeline solvers                                          |
| `network`                    | `PayloadCompression` / `CompressedPayload`: compressed custom payloads                        |

### Which jar for which Minecraft version

Each jar is boot-tested on a real NeoForge server for every version it declares:

| Jar        | Minecraft          |
|------------|--------------------|
| `1.21.1`   | 1.21 – 1.21.6      |
| `1.21.10`  | 1.21.7 – 1.21.10   |
| `1.21.11`  | 1.21.11            |
| `26.1`     | 26.1 – 26.3        |

The groups are defined by `game_versions` in `mod.stonecutter.properties.toml`.

### Runtime selection and fallbacks

Everything is chosen at startup and logged once. No configuration or launch flags are needed:

| Feature      | Preferred                                           | Fallback                          |
|--------------|-----------------------------------------------------|-----------------------------------|
| Memory       | FFM (26.x build; 1.21.x builds with `--enable-preview`) | `sun.misc.Unsafe`, then Java heap |
| SIMD         | `jdk.incubator.vector` (`--add-modules`)            | Scalar code, same results         |
| Compression  | zstd (native, needs FFM)                            | deflate (JDK zlib)                |
| Network wire | Always deflate, so client and server always agree   |                                   |

Force the pure-Java paths with `-Dceleris.compatMode=true`.

### ⚡ Maximum performance (optional JVM flags)

Celeris works without flags. These unlock its fastest paths (verified on real NeoForge servers):

| Minecraft (Java)       | Add to your JVM arguments                                                              | Unlocks                |
|------------------------|----------------------------------------------------------------------------------------|------------------------|
| 1.21.x (Java 21)       | `--enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | FFM, native zstd, SIMD |
| 26.x and up (Java 25+) | `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED`                 | SIMD (FFM and zstd are already on) |

Where to put them: **Modrinth App** → instance → *Settings → Java and memory → Java arguments*; **CurseForge App** →
*Settings → Minecraft → Additional arguments*; **Prism Launcher** → instance → *Settings → Java → JVM arguments*;
**servers** → `user_jvm_args.txt`. Use `--enable-preview` only with the Java 21 that Minecraft 1.21.x ships with.

## Using Celeris in your mod

```kotlin
repositories {
    maven("https://panzerdevorg.github.io/Celeris/maven") {
        content { includeGroup("com.panzer.mods") }
    }
}

dependencies {
    // one artifact per Minecraft version: celeris-<mc version>
    implementation("com.panzer.mods:celeris-1.21.1:0.1.0")
}
```

Declare it as a required dependency in your `neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "celeris"
type = "required"
versionRange = "[0.1.0,)"
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
./gradlew build                                   # every Minecraft version, with tests
./gradlew :1.21.1:runClient                       # dev client for one version
./gradlew publishToMavenLocal                     # use your local build from other mods
./gradlew buildAndCollect -Pceleris.fatJar=true   # release jars in build/libs/<version>/
```

Native zstd libraries for every supported platform are committed under `natives/<os>/<arch>/` and bundled into the
jar.

## Docs, changelogs and the Modrinth page

Everything published outside GitHub lives in [`docs/`](./docs): one changelog per version in
`docs/changelogs/<version>.md`, and the Modrinth page in `docs/modrinth/description.md` (synced on every push to
`master`). See [`docs/README.md`](./docs/README.md).

## Releasing

Add `docs/changelogs/<version>.md`, bump `version` under `[mod]` in `mod.stonecutter.properties.toml`, commit,
then push a matching tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

CI (`.github/workflows/package.yml`) builds and tests every version on each push. On a `v*` tag it also publishes all
versions to the GitHub Pages Maven repository above and uploads each jar to Modrinth and CurseForge with that
changelog. It refuses to publish if the tag doesn't match the mod version or the changelog file is missing.

## License

| Content               | License                                                                                                 |
|-----------------------|---------------------------------------------------------------------------------------------------------|
| Source code           | [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) — see [`LICENSE-AGPL`](./LICENSE-AGPL)           |
| Artwork and branding  | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) — see [`LICENSE-CC`](./LICENSE-CC) |
| Bundled zstd binaries | [BSD-3-Clause](https://github.com/facebook/zstd/blob/dev/LICENSE) (zstd)                                |

See [`LICENSE`](./LICENSE) for the full summary.
