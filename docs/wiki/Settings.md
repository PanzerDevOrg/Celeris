# Settings

Celeris has one setting: the network compression level.

## What it does

When Celeris sends data over the network, it can compress it first. The level sets how hard it tries.

| Level | What happens |
|---|---|
| 0 | No compression. Least CPU work, most data sent. |
| 1 | The default. Fastest compression. |
| 9 | Smallest data sent. Most CPU work. |

Levels in between trade CPU work for size. Higher numbers give smaller data, and use more CPU.

Only the sender's level matters. The level on a server controls what the server sends. The level on a player's game controls what that player sends. The two sides do not need to match.

## Change it on NeoForge

**In the game:** open Mods, then Celeris, then Config. Change the value and save. It applies to the next packet.

**In the file:** open `config/celeris-common.toml` and set the value:

```toml
compressionLevel = 1
```

On newer NeoForge versions (26.3 and later), the file is named `celeris-local.toml` instead. If you edit the file by hand, restart the game or server.

## Change it on Fabric

**With Mod Menu:** open Mods, then Celeris, then Config. Changes apply at once and are saved for you. You need [Mod Menu](https://modrinth.com/mod/modmenu) for this screen.

**In the file:** Celeris creates `config/celeris.properties` the first time it starts. Open it and set:

```properties
compressionLevel=1
```

Restart the game or server after you edit this file by hand.

## Allowed values

Use a whole number from 0 to 9.

On Fabric, a number outside that range is replaced by the nearest allowed value, 0 or 9, and a warning goes to the log. A value that is not a number is replaced by the default, 1.

## Examples

**The server CPU is busy, and bandwidth is not a concern.** Use 0. Celeris does not compress the data it sends.

**Players have slow connections, and the server has CPU to spare.** Try a higher value, such as 5 or 9.

**You are not sure.** Keep the default, 1.
