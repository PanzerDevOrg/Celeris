# Settings

- **Network compression level** (0 to 9, default 1): how hard Celeris compresses the packets it sends. 0 sends them
  uncompressed (least CPU, most bandwidth), 1 is the fastest, 9 gives the smallest packets. Only the sender's setting
  matters, so a server and its players can each pick their own.
  - **NeoForge:** Mods > Celeris > Config, or `compressionLevel` in `config/celeris-common.toml`. Takes effect on the
    next packet.
  - **Fabric:** with [Mod Menu](https://modrinth.com/mod/modmenu), Mods > Celeris > Config (takes effect on the next
    packet); or `compressionLevel` in `config/celeris.properties` (created on first start; restart after editing it
    by hand).

[Back to Home](Home.md)
