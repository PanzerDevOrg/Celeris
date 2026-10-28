# Using Celeris in your mod

```kotlin
repositories {
    maven("https://bichal.github.io/Celeris/maven") {
        content { includeGroup("net.bichal.mods") }
    }
}
dependencies {
    implementation("net.bichal.mods:celeris-1.21.1:0.3.1") // NeoForge: celeris-<minecraft version>
    // Fabric (Loom): modImplementation("net.bichal.mods:celeris-1.21.1-fabric:0.3.1")
}
```

Source, examples and the package map: **[github.com/bichal/Celeris](https://github.com/bichal/Celeris)**

Declare it as a required dependency in your `neoforge.mods.toml` (Fabric: `"depends": { "celeris": ">=0.3.1" }` in
`fabric.mod.json`):

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

Quick example: items in pipes (server tick, then what to send players).

```java
var migrator = new TransitMigrator((handle, speed, nodeRaw) -> dropInWorld(handle, nodeRaw));
var graph = new SegmentedGraphImpl<>(topology, TransitPayload::new, migrator); // pipes keep their items when edited
var solver = new TransitSolver(TransitConfig.defaults(), new RoundRobinRouter(), endpoints, migrator);
solver.bind(graph);
var recorder = new TransitDeltaRecorder(0L, bytes -> send(TransitDeltaPackets.encode(bytes)));
solver.setEvents(recorder);
migrator.setEvents(recorder, solver::tickCount);

graph.addNodes(ids, masks, count);      // a whole chunk of pipes in one pass
solver.insert(node, direction, handle); // an item enters the pipe leaving `node`
solver.tick();                          // once per server tick
recorder.drain(solver.tickCount());     // sends only what changed
// client: TransitDeltaPackets.decode(payload, clientItemTable); position = TransitMotion.progress(...)
```

All of it runs on one thread (normally the server thread) and has a pure-Java path. More complete, runnable examples (pipes, wires, ring buffer, compressor, memory bank) live in
[`example/`](./example).

| Package | Contents |
|---|---|
| `core.memory.backend` | `MemoryBackend` with automatic selection: FFM, then Unsafe, then **heap** |
| `core.memory` | `PacketPipeline`, `MemoryBus`, zstd (native) / deflate compression codecs |
| `core.concurrency` | `MpscRingBuffer`: lock-free multi-producer/single-consumer ring on off-heap memory |
| `framework.async` | `AsyncResultQueue`: hand results from worker threads to the game/render thread |
| `framework.ticking` | `IdleTickFilter`: skip work while nothing is pending |
| `framework.capability/query` | Capability caching and throttled queries |
| `api.simd` | `VectorOperations`: SIMD via `jdk.incubator.vector`, scalar fallback with identical results |
| `core.topology`, `graph.*` | Network topology plus segmented (pipes, wires) and discrete (per-node signal) graphs |
| `pipeline.*` | Event-driven and continuous (SIMD) pipeline solvers; `pipeline.transit`: item transport (`TransitSolver`, routers, migration, persistence) |
| `api.transit` | What a client needs to draw transported items: `TransitMotion`, `TransitDelta`, `ClientItemTable` |
| `network` | `PayloadCompression` / `CompressedPayload`: compressed custom payloads; `TransitDeltaPackets` for item messages |
| `physics` | Batch physics engine: SoA bodies, native C++ kernel or pure-Java kernel, broadphase, entity bridge ([`native/`](./native)) |

Everything is chosen at startup and logged once. `-Dceleris.compatMode=true` forces the pure-Java paths.

[Back to Home](Home.md)
