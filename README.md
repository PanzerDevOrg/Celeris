![Celeris](./docs/media/banner.png)

<p align="center">
<img src="https://img.shields.io/badge/Minecraft-1.21_--_26.3-3c8527?style=for-the-badge" alt="Minecraft 1.21 to 26.3">
<img src="https://img.shields.io/badge/NeoForge_and_Fabric-Client_and_Server-e8710a?style=for-the-badge" alt="NeoForge and Fabric, client and server">
<img src="https://img.shields.io/badge/Type-Library-7c3aed?style=for-the-badge" alt="Library">
</p>

<p align="center">
<!-- publish:only modrinth -->
<a href="https://modrinth.com/mod/sQphaM3I"><img src="https://img.shields.io/badge/Download_on-Modrinth-1bd96a?style=for-the-badge&logo=modrinth&logoColor=white" alt="Download on Modrinth"></a>
<!-- /publish:only -->
<!-- publish:only curseforge -->
<a href="https://www.curseforge.com/projects/1722411"><img src="https://img.shields.io/badge/Download_on-CurseForge-f16436?style=for-the-badge&logo=curseforge&logoColor=white" alt="Download on CurseForge"></a>
<!-- /publish:only -->
</p>

> **Celeris is a performance library for NeoForge and Fabric mods.** It gives other mods fast building blocks so they can do heavy work without slowing the game down. On its own it adds nothing you can see in game: you install it because a mod you want needs it.

## 🎮 Mods that use it

| Mod | What it does for you |
|---|---|
| 🧱 **[Tessera](https://github.com/bichal/Tessera)** | Shrinks Minecraft's textures on the graphics card by **up to 75%**, with no visible difference. |

## ⚡ What it gives those mods

- 🧵 **Work off the main thread.** Heavy jobs run on worker threads and their results come back to the game thread
  through lock-free queues, without stalling a tick or a frame.
- 🧠 **Less garbage-collector pressure.** Large buffers live in off-heap memory, so big data does not cause GC pauses.
- 📐 **Vector math.** Bulk number crunching uses the CPU's SIMD units when Java allows it, and plain loops when it
  does not.
- 🗜️ **Compression.** Native zstd for mods' own data (no JVM flags needed) and deflate for network packets.
- 📦 **Items in pipes.** Networks that carry items from block to block, with no entity per item, that keep the items
  when pipes are placed, broken or saved.
- 🔌 **Signal networks.** Redstone-style graphs that only recompute what a change touches.
- 🏃 **Batch physics.** Thousands of entities and dropped items moved in one pass, following vanilla's movement rules.

## 🛡️ Safe everywhere

- ✅ Every fast path has a pure-Java fallback. If something can't run the fast way on your machine, Celeris uses the
  slower one instead of crashing.
- ✅ Optional on both sides of a connection: players without Celeris can join a server that has it, and you can join
  any server with it. A Fabric client can also join a NeoForge server with Celeris.
- ✅ Network payloads are size-capped and their headers are checked before anything uses them.
- ✅ The native code is checked with AddressSanitizer and UBSan in CI, and its results match the Java version bit
  for bit.

## 🏁 How fast

Each building block, measured against what a mod would otherwise use (JMH, Java 21, a 4-core machine; method and
full tables in the [benchmarks page](https://github.com/bichal/Celeris/blob/main/docs/benchmarks.md)):

- 🗜️ **12.8× faster compression** than Java's built-in deflate (what Minecraft uses) for nearly the same size, and
  **3.1× faster decompression**. As fast as zstd-jni in a jar a quarter of its size.
- 🏃 **32,768 dropped items moved in 0.88 ms** per tick on 3 threads with the native kernel, 5.3× faster than the
  same rules in Java on one thread and under 2 % of a tick.
- 🧲 **Item piles no longer stall**: finding the touching items in a pile of 16,384 takes 0.24 ms instead of up to a
  second.
- 🧵 **4.9× faster hand-off** from one worker thread to the game thread than JCTools, and still ahead with two or
  three workers.

![Compression benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/compression.png)
![Physics benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/physics.png)
![Broadphase benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/broadphase.png)
![Queue benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/queues.png)

## 📦 Requirements

- 🟩 **Minecraft:** 1.21 to 26.3. Download the file made for your version.
- 🔶 **Loader:** NeoForge or Fabric (with [Fabric API](https://modrinth.com/mod/fabric-api)), on the client and on the server.
- ☕ **Java:** 21 for 1.21.x, 25 for 26.x.

## ⚙️ Settings

- **Network compression level** (0 to 9, default 1): how hard Celeris compresses the packets it sends. 0 sends them
  uncompressed, 1 is the fastest, 9 gives the smallest packets. Only the sender's setting matters.
  - **NeoForge:** Mods → Celeris → Config, or `compressionLevel` in `config/celeris-common.toml`.
  - **Fabric:** with [Mod Menu](https://modrinth.com/mod/modmenu), Mods → Celeris → Config; or `compressionLevel`
    in `config/celeris.properties` (restart after editing it by hand).

Optional JVM flags are on the [wiki](https://github.com/bichal/Celeris/wiki/JVM-flags).

## 🔧 Native code

Celeris ships two native libraries, built from public source by GitHub Actions on each platform's own runner, from
the same tagged commit as every file published here:

- **zstd 1.5.7** (Meta's Zstandard, BSD-3-Clause), compiled from the official release tarball and checked against its
  SHA-256, plus a few small functions of Celeris's own that let Java 21 call it without extra flags:
  [`native/zstd`](https://github.com/bichal/Celeris/tree/main/native/zstd).
- **The physics kernel**, Celeris's own C++ code: [`native/`](https://github.com/bichal/Celeris/tree/main/native).

GitHub releases also carry a Java-only file with no native code at all. See [Native libraries](https://github.com/bichal/Celeris/wiki/Native-libraries).

## 📚 Learn more

The **[Celeris wiki](https://github.com/bichal/Celeris/wiki)** has the settings, JVM flags, the guide for mod authors, performance numbers and how to
build the project.

<p align="center">
<a href="https://github.com/bichal/Celeris/wiki"><img src="https://img.shields.io/badge/Wiki-Guide_--_source-24292f?style=for-the-badge&logo=github&logoColor=white" alt="Guide and source on GitHub"></a>
<a href="https://github.com/bichal/Celeris/issues"><img src="https://img.shields.io/badge/Found_a_bug-Report_it-d73a4a?style=for-the-badge&logo=github&logoColor=white" alt="Report a bug"></a>
<a href="https://github.com/bichal/Celeris/tree/main/docs/changelogs"><img src="https://img.shields.io/badge/Whats_new-Changelog-2f81f7?style=for-the-badge&logo=github&logoColor=white" alt="Changelog"></a>
</p>

<!-- bichal:license -->
## License

| Content | License |
|---|---|
| Source code | [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html), see [`LICENSE-AGPL`](./LICENSE-AGPL) |
| Artwork, branding and documentation | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/), see [`LICENSE-CC`](./LICENSE-CC) |
| Zstandard (zstd) (bundled) | [BSD-3-Clause](https://github.com/facebook/zstd), see [`NOTICE`](./NOTICE) |

**Source code:** you may study, modify and redistribute it under the AGPL; if you run a modified version as a network service, its source must be available to that service's users.

**Artwork:** credit Bichal, no commercial use without permission, and share derivatives under the same license.

See [`LICENSE`](./LICENSE) for the full summary.
<!-- /bichal:license -->

<!-- publish:on -->

---

<!-- bichal:footer -->
Code: **AGPL-3.0** · Art: **CC BY-NC-SA 4.0** · Made by **Bichal**
<!-- /bichal:footer -->
