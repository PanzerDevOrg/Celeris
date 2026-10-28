![Celeris](./docs/media/banner.png)

<p align="center">
<img src="https://img.shields.io/badge/Minecraft-1.21_--_26.3-3c8527?style=for-the-badge" alt="Minecraft 1.21 to 26.3">
<img src="https://img.shields.io/badge/NeoForge_and_Fabric-Client_and_Server-e8710a?style=for-the-badge" alt="NeoForge and Fabric, client and server">
<img src="https://img.shields.io/badge/Type-Library-7c3aed?style=for-the-badge" alt="Library">
</p>

> **Celeris is a performance library for NeoForge and Fabric mods.** It gives other mods fast building blocks so they can do heavy work without slowing the game down. On its own it adds nothing you can see in game: you install it because a mod you want needs it.

<!-- publish:only modrinth -->
Download on [Modrinth](https://modrinth.com/mod/sQphaM3I).
<!-- /publish:only -->

<!-- publish:only curseforge -->
Download on [CurseForge](https://www.curseforge.com/projects/1722411).
<!-- /publish:only -->

<!-- publish:only github -->
Download on [GitHub](https://github.com/bichal/Celeris/releases).
<!-- /publish:only -->

## What it does

- Runs heavy work on other threads, so the game does not stall.
- Keeps large data off the Java heap, so the garbage collector has less to do.
- Compresses data quickly, with zstd for mods and deflate for network packets.
- Moves thousands of entities and dropped items in one pass.

Celeris adds nothing you can see in game on its own. Install it because a mod you use needs it.

## Requirements

- Minecraft 1.21 to 26.3, NeoForge or Fabric (with Fabric API), on the client and the server.
- Java 21 for Minecraft 1.21.x, Java 25 for 26.x.

## Learn more

The **[Celeris wiki](https://github.com/bichal/Celeris/wiki)** has the settings, optional JVM flags, the mod author
guide, performance numbers and how to build the project.

<p align="center">
<a href="https://github.com/bichal/Celeris/issues"><img src="https://img.shields.io/badge/Found_a_bug-Report_it-d73a4a?style=for-the-badge&logo=github&logoColor=white" alt="Report a bug"></a>
<a href="https://github.com/bichal/Celeris/tree/master/docs/changelogs"><img src="https://img.shields.io/badge/Whats_new-Changelog-2f81f7?style=for-the-badge&logo=github&logoColor=white" alt="Changelog"></a>
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
Code: **AGPL-3.0** | Art: **CC BY-NC-SA 4.0** | Made by **Bichal**
<!-- /bichal:footer -->
