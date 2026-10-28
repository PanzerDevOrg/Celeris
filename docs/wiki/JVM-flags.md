# JVM flags

None are required. These open the fast paths that the JVM keeps closed by default:

| Java | JVM arguments | Opens |
|---|---|---|
| 21 (Minecraft 1.21.x) | `--enable-preview --add-modules=jdk.incubator.vector` | FFM memory, SIMD (native physics and native zstd work without flags, over JNI) |
| 25 (Minecraft 26.x) | `--add-modules=jdk.incubator.vector` | SIMD (the rest is already open on 25) |

On NeoForge, leave `--enable-native-access` out: NeoForge loads mods as named modules that the flag cannot cover, and on
Java 21 it then blocks their FFM calls (native physics and zstd keep working: both use JNI, which the flag does not affect).
Fabric loads mods on the class path, so there `--enable-native-access=ALL-UNNAMED` is harmless and silences the JVM's
native-access warnings.

Modrinth App: *Settings > Java and memory*. CurseForge: *Settings > Minecraft > Additional arguments*. Prism: instance
*Settings > Java*. Servers: `user_jvm_args.txt`. `--enable-preview` belongs to Java 21 only.

[Back to Home](Home.md)
