# Celeris

**A performance library for NeoForge mods.** Celeris gives mod developers off-heap memory, lock-free concurrency, SIMD
math and fast compression, each with a pure-Java fallback, so everything works on any Java 21+ install with **no launch
flags**.

> 📚 **This is a library.** It adds nothing to the game by itself. You only need it when another mod requires it, for
> example [Tessera](https://modrinth.com/project/olpctCIW).

---

## ✨ What's inside

| | |
|---|---|
| 🧠 **Off-heap memory** | FFM on Java 22+, `Unsafe` on Java 21, heap fallback, chosen automatically |
| ⚡ **Lock-free queues** | `MpscRingBuffer` and `AsyncResultQueue` to move results from worker threads to the game thread |
| 🧮 **SIMD math** | `jdk.incubator.vector` when enabled, identical scalar results otherwise |
| 📦 **Compression** | zstd and deflate codecs, plus compressed network payloads |
| 🕸️ **Graphs & networks** | Segmented (pipes, wires) and discrete (signals) graphs with pipeline solvers |

## 🛡️ Safe by design

- Every native or off-heap feature falls back to pure Java; Celeris never crashes the game because a backend is
  unavailable.
- Network traffic always uses deflate, so clients and servers on different Java versions always understand each
  other.
- Incoming payloads are size-capped and malformed packets are dropped.

## 📋 Requirements

| Minecraft | 1.21 – 1.21.11 · 26.1 – 26.3 (pick the file for your version) |
|---|---|
| Loader | NeoForge |
| Java | 21 (Minecraft 1.21.x) · 25 (26.x) |
| Side | Client and server |

## ⚡ Maximum performance (optional)

Celeris works without any setup. These JVM flags unlock its fastest code paths:

| Minecraft (Java) | Add to your JVM arguments | Unlocks |
|---|---|---|
| 1.21.x (Java 21) | `--enable-preview --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | Off-heap FFM, native zstd, SIMD |
| 26.x and up (Java 25+) | `--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | SIMD (FFM and zstd already on) |

**Modrinth App**: instance → *Settings → Java and memory → Java arguments*. **Servers**: `user_jvm_args.txt`.
Without the flags nothing breaks; each feature uses its pure-Java fallback.

## 👩‍💻 For developers

```kotlin
repositories {
    maven("https://panzerdevorg.github.io/Celeris/maven")
}
dependencies {
    implementation("com.panzer.mods:celeris-1.21.1:0.1.0")
}
```

Docs, examples and source: **[github.com/PanzerDevOrg/Celeris](https://github.com/PanzerDevOrg/Celeris)**

---

Source code is licensed under **AGPL-3.0**; artwork under **CC BY-NC-SA 4.0**. Made by **Panzer**.
