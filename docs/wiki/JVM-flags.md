# JVM flags

Celeris works without any startup flags. The flags on this page turn on faster code paths that Java keeps closed by default. Add them if you want the extra speed. Skip them if you do not.

## Which flags to use

| Minecraft | Java | Flags | What they turn on |
|---|---|---|---|
| 1.21.x | 21 | `--enable-preview` | The FFM code paths. In the current release this is also what the native physics kernel needs. See the note below. |
| 1.21.x | 21 | `--enable-preview --add-modules=jdk.incubator.vector` | The same, plus faster SIMD math. |
| 26.x | 25 | `--add-modules=jdk.incubator.vector` | Faster SIMD math. Java 25 already has the other paths open. |

## Note for version 0.3.1.1

In version 0.3.1.1, the native parts (the physics kernel and zstd) were built with function names that do not match the Java classes. Java cannot link them, so Celeris uses the FFM path or the Java code instead. On Java 21, `--enable-preview` lets Celeris use FFM, so the native physics kernel can run.

The fix is already in the source code and will ship in the next release. After that, the native parts load without flags, and these flags only add FFM memory and SIMD math.

## Advanced switches

These are for testing and for solving problems. You do not need them normally.

| Switch | What it does |
|---|---|
| `-Dceleris.compatMode=true` | Uses only plain Java code. Slower, but it works everywhere. |
| `-Dceleris.physics.engine=java` | Uses the Java physics kernel, even for a mod that asked for the native one. |
| `-Dceleris.physics.engine=auto` | Lets every mod use the native physics kernel (JNI first, then FFM, then Java). Without this switch the native kernel runs only for a mod that asks for it, such as Velox. |
| `-Dceleris.physics.engine=jni` or `ffm` | Lets the native kernel run through that binding only. If it cannot load, the Java kernel takes over. |
| `-Dceleris.physics.engine=off` | Turns the physics engine off. Mods that use it fall back to their normal code. |
| `-Dceleris.physics.isa=avx2` | Picks the native instruction set. Choose one of `generic`, `avx2`, `avx512` or `neon`. |
| `-Dceleris.physics.threads=4` | Sets how many worker threads the physics uses. |

## Where to add the flags

- **Modrinth App:** Settings, then Java and memory.
- **CurseForge:** Settings, then Minecraft, then Additional arguments.
- **Prism Launcher:** open the settings of your instance, then Java.
- **Server:** open the file `user_jvm_args.txt` in the server folder, and put the flags on one line.

Example for a Java 21 server:

```text
--enable-preview --add-modules=jdk.incubator.vector
```

## Two things to avoid

- Do not add `--enable-native-access` on NeoForge. On Java 21 it blocks the FFM path for NeoForge mods. Fabric is fine with `--enable-native-access=ALL-UNNAMED`, which also silences native access warnings.
- Do not add `--enable-preview` on Java 25. It is only for Java 21.

## Check that it worked

Start the game or server, and search the log for these lines:

- `Celeris memory backend: FFM` means FFM memory is on.
- `Celeris physics engine` followed by a name that starts with `native` means the native physics kernel is on. A name of `java` means the Java kernel is used, which is the default for a mod that does not ask for the native one.
