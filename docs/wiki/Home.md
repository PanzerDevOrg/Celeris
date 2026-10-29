# Celeris wiki

Celeris is a helper library for NeoForge and Fabric mods. It makes heavy work faster, so the game stays smooth.

On its own, Celeris adds nothing you can see in the game. Other mods use it. You install it when a mod asks for it.

- Minecraft: 1.21 to 26.3
- Mod loaders: NeoForge, or Fabric with Fabric API
- Runs on: the client and the server
- Java: 21 for Minecraft 1.21.x, 25 for Minecraft 26.x
- Current version: 0.3.1.1

## Where to start

**I play Minecraft.** You usually do not need to do anything. If a mod asks for Celeris, download the file for your Minecraft version. See [Requirements and files](Requirements-and-files.md).

**I run a server.** Read these pages in this order:

1. [Requirements and files](Requirements-and-files.md): pick the right file.
2. [Settings](Settings.md): choose how much the server compresses network data.
3. [JVM flags](JVM-flags.md): optional startup options for faster code.
4. [Troubleshooting](Troubleshooting.md): what the log says, and how to fix common problems.

**I make a mod that uses Celeris.**

- [Using Celeris in your mod](Using-Celeris-in-your-mod.md): add Celeris as a dependency.
- [Examples](Examples.md): short code you can copy.
- [Performance](Performance.md): how fast each part is.
- [Native libraries](Native-libraries.md): the parts written in C and C++.

**I work on Celeris itself.**

- [Building](Building.md): build the project from source.
- [Releasing](Releasing.md): how a new version is published.

## Need help?

Check [Troubleshooting](Troubleshooting.md) first. If your problem is not there, [open an issue on GitHub](https://github.com/bichal/Celeris/issues). Include the lines from the log that start with Celeris.

## What changed

Each version has a short changelog in [docs/changelogs](https://github.com/bichal/Celeris/tree/main/docs/changelogs).
