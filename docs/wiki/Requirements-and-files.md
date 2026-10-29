# Requirements and files

## What you need

- Minecraft 1.21 to 26.3.
- NeoForge, or Fabric with [Fabric API](https://modrinth.com/mod/fabric-api).
- Java 21 for Minecraft 1.21.x. Java 25 for Minecraft 26.x.
- Celeris on each side that runs a mod that needs it: the client, the server, or both.

## Pick your file

Each file covers a range of Minecraft versions. Find your version in the table, and download that file.

| Minecraft version | NeoForge file | Java |
|---|---|---|
| 1.21 to 1.21.6 | `celeris-0.3.1.1+1.21.1.jar` | 21 |
| 1.21.7 to 1.21.10 | `celeris-0.3.1.1+1.21.10.jar` | 21 |
| 1.21.11 | `celeris-0.3.1.1+1.21.11.jar` | 21 |
| 26.1 to 26.3 | `celeris-0.3.1.1+26.1.jar` | 25 |

**Example 1:** You play Minecraft 1.21.4 with NeoForge. 1.21.4 is in the first row, so you download `celeris-0.3.1.1+1.21.1.jar`.

**Example 2:** Your server runs Minecraft 26.2 with NeoForge. 26.2 is in the last row, so you download `celeris-0.3.1.1+26.1.jar`.

## Fabric

Fabric files have `-fabric` at the end of their name. Use the same row of the table as above.

**Example:** You play Minecraft 26.2 with Fabric. Download `celeris-0.3.1.1+26.1-fabric.jar`.

You also need Fabric Loader 0.16 or newer, and Fabric API.

## Put the file in the mods folder

Copy the jar into the `mods` folder of your game or server. Remove any older Celeris jar first, so only one is there.

## Smaller downloads

The GitHub release page has two kinds of smaller files:

- One file per system: Windows, Linux or macOS. Each one has only the native parts for its system.
- A Java only file. It has no native parts, so it works on every system, but Celeris then uses its Java code paths.

Use these only if you want to save space. The normal file works everywhere.

Every file was started on each Minecraft version in its row before it was released.
