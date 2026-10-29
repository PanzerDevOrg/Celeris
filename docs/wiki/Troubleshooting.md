# Troubleshooting

Start with the log. Celeris writes short lines when it starts, and each line says what it chose. Search the log for lines that start with `Celeris`.

## A normal start

Here is an example of a start where the native parts work:

```text
Celeris engine initializing
Celeris memory backend: FFM
Celeris compression codec: zstd
Celeris network compression level: 1
Celeris physics engine: native-jni-avx2
Celeris engine working
```

The line `Celeris engine working` means Celeris started. The physics engine line may appear later, when the first physics work starts.

## What the values mean

| Log line | Good value | Other values |
|---|---|---|
| `Celeris memory backend` | `FFM` | `Unsafe` works without flags on any Java version. `Heap (compat mode)` uses plain Java only, and is the slowest. |
| `Celeris compression codec` | `zstd` | `deflate` is used when zstd is not available. It works too. |
| `Celeris physics engine` | A name that starts with `native` | `java` is the Java kernel. It works, but it is slower. |

## Common problems

### A mod says Celeris is missing

The mod needs Celeris, and it is not installed, or the file is for a different Minecraft version. Download the file for your version from [Requirements and files](Requirements-and-files.md), and put it in the `mods` folder.

### The physics engine says java

This is not an error. Celeris uses the Java kernel, which is slower.

To get the native kernel:

- On Java 21, add `--enable-preview`. See [JVM flags](JVM-flags.md).
- On NeoForge, remove `--enable-native-access` from the arguments if you added it.
- On Java 25, there is nothing to add. If the line still says `java`, report it with your log.

### I changed a setting, and nothing happened

- On NeoForge, a change made in the game applies to the next packet. If you edited the file by hand, restart.
- On Fabric, restart after you edit `celeris.properties` by hand.

### The Fabric game will not start with Celeris

Check that you took the file that ends in `-fabric.jar`, and that Fabric API is installed.

### The server is still slow

Celeris speeds up some parts of the game, not all of them. See [Performance](Performance.md) to learn what Celeris helps with.

## Known issue in version 0.3.1.1

In version 0.3.1.1, the native parts use function names that do not match the Java classes. Java cannot link them, so the native physics kernel and native zstd are not used. Celeris falls back to the FFM path or to Java code, and it keeps working.

On Java 21, `--enable-preview` lets Celeris use the FFM path, so the native physics kernel can run. See [JVM flags](JVM-flags.md). The fix is in the source code and will ship in the next release.

## Frequently asked questions

**Do I need Celeris?**
Only if a mod you use asks for it. On its own, Celeris adds nothing to the game.

**Is it safe to remove?**
Yes, if no installed mod needs it. Mods that need it will not load without it.

**Does Celeris change how the game looks or plays?**
No.

**Do players need it?**
Only if the mods they play need it.

**Which Java do I need?**
Java 21 for Minecraft 1.21.x. Java 25 for Minecraft 26.x.

**Where do I report a bug?**
On the [issues page](https://github.com/bichal/Celeris/issues). Include the Celeris lines from the log, and your Minecraft and loader versions.
