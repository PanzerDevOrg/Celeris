# Using Celeris in your mod

This page shows how to add Celeris to your mod as a dependency. For short code you can copy, see [Examples](Examples).

## 1. Add the repository and the dependency

For NeoForge, in `build.gradle.kts`:

```kotlin
repositories {
    maven("https://bichal.github.io/Celeris/maven") {
        content { includeGroup("net.bichal.mods") }
    }
}

dependencies {
    // One artifact per Minecraft build: celeris-1.21.1, celeris-1.21.10, celeris-1.21.11 or celeris-26.1
    implementation("net.bichal.mods:celeris-1.21.1:0.3.1.1")
}
```

For Fabric with Fabric Loom, use the `-fabric` artifact:

```kotlin
dependencies {
    modImplementation("net.bichal.mods:celeris-1.21.1-fabric:0.3.1.1")
}
```

## 2. Declare Celeris as required

For NeoForge, add this to `neoforge.mods.toml`. Replace `yourmod` with your own mod id.

```toml
[[dependencies.yourmod]]
modId = "celeris"
type = "required"
versionRange = "[0.3.1.1,)"
ordering = "AFTER"
side = "BOTH"
```

For Fabric, add `"celeris": ">=0.3.1.1"` to the `depends` section of `fabric.mod.json`.

Use the oldest Celeris version your mod was tested with. 0.3.1.1 is the current one.

## 3. Know the main packages

| Package | What it is for |
|---|---|
| `core.memory` | Off-heap memory, and compression with zstd (or deflate for network packets). |
| `core.concurrency` | `MpscRingBuffer`, a lock-free queue on off-heap memory. Many threads can add to it, and one thread reads from it. |
| `framework.async` | `AsyncResultQueue`: hands results from worker threads to the game thread. |
| `framework.ticking` | `IdleTickFilter`: skips work while nothing is waiting. |
| `framework.capability` and `framework.query` | Capability caching and throttled queries. |
| `api.simd` | `VectorOperations`: SIMD math, with a plain Java fallback that gives the same results. |
| `core.topology` and `graph` | Network topology, and graphs for pipes, wires and signals. |
| `pipeline` | Solvers that move items through pipes, such as `TransitSolver`. |
| `api.transit` | What a client needs to draw moving items. |
| `network` | Compressed payloads for your own packets. |
| `physics` | Batch physics for many bodies, with a native C++ kernel or a Java kernel. |

Most parts are used from one thread, normally the server or game thread. The examples say which thread each call belongs on.

## Options chosen at startup

Celeris chooses its memory, math and physics paths once, at startup, and logs its choice. To force the plain Java paths, start the game with `-Dceleris.compatMode=true`. See [JVM flags](JVM-flags).
