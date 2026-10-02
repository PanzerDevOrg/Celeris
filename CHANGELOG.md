# Changelog

Each release section below is published as the changelog on Modrinth and CurseForge.

## [0.1.0] - 2026-10-02

# Celeris v0.1.0 Changelog

## First public release of **Celeris**, a performance library for NeoForge mods: off-heap memory, lock-free concurrency, SIMD math and fast compression, each with a pure-Java fallback.

> Library mod: it adds no gameplay by itself. Install it when another mod (such as **Tessera**) requires it.

---

## Key Features

* **Off-Heap Memory Backends**: FFM on Java 22+, `sun.misc.Unsafe` on Java 21 (no launch flags), and a pure-Java heap fallback, selected automatically at startup.
* **Lock-Free Concurrency**: `MpscRingBuffer` and `AsyncResultQueue` to hand work from worker threads back to the game thread without locks.
* **SIMD Math**: `VectorOperations` uses `jdk.incubator.vector` when it is enabled and a scalar path with identical results otherwise.
* **Compression**: zstd (native) and deflate codecs, plus compressed network payloads. Network traffic always uses deflate, so clients and servers on different Java versions always understand each other.
* **Graph & Network Utilities**: Segmented (pipes, wires) and discrete (per-node signal) graphs on a shared topology, with event-driven and continuous pipeline solvers.
* **Multi-Version**: Builds for Minecraft 1.21.1, 1.21.3, 1.21.6, 1.21.10, 1.21.11 and 26.1.

---

## Safety

* Every native or off-heap path falls back to pure Java instead of crashing.
* Network payloads are size-capped and malformed packets are dropped, never thrown into the game thread.
