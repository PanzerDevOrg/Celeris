# Performance

This page gives the main numbers. The [benchmark page](Benchmarks) has every table, the method, and the costs.

The numbers come from JMH benchmarks, run in October 2026 on a shared 4 core cloud machine with Java 21, with Celeris 0.2.3 (the physics kernel has since started checking every item for impossible values; it is within about 3 percent of the build before those checks, measured on 2026-10-30). The engine comparison in the [Benchmarks](Benchmarks) page can be reproduced with `./gradlew benchmarkTest`. Your numbers will differ. Read differences under about 20 percent as a tie. The ratios are what matter.

## Compression

| Job | Celeris | Java's built-in code | Result |
|---|---|---|---|
| Compress 38 KiB of mod data | Native zstd: 542 MiB per second | Java Deflater: 42 MiB per second | 12.8 times faster, for almost the same size |
| Decompress the same data | 1,296 MiB per second | Java Inflater: 417 MiB per second | 3.1 times faster |

Celeris is about as fast as zstd-jni, the usual Java zstd library. Its jar is about 2 MB, against 7.4 MB for zstd-jni.

## Dropped items

Celeris has three physics engines: the Java kernel, and the native kernel through JNI or through FFM. They give the same results bit for bit. One thread, AVX2, measured on 2026-10-30:

- 10,000 dropped items take 2.08 ms per tick with the Java kernel, 1.02 ms with JNI and 1.07 ms with FFM. 32,768 items take 6.97 ms, 3.41 ms and 3.52 ms. The native kernel is about twice as fast as the Java kernel, and JNI and FFM are level.
- **That does not make a real server faster.** The step is 1 to 2 percent of the server thread with native code and about 7 percent with Java, and in the server test below the three engines give the same tick to within noise. So the Java kernel is the default, and a mod asks for the native one when it helps that mod.
- Finding the items that touch each other in a pile of 16,384 takes 0.23 ms (JNI, FFM) or 0.24 ms (Java), a tie. Before, the same search could take close to a second.

## Passing results between threads

- One worker thread handing results to the game thread: 39.5 million per second. JCTools, a well known lock-free queue library, does 8.0 million per second. That is 4.9 times faster.
- With two or three workers, Celeris is still 1.3 to 1.5 times faster than JCTools.

## Items on pipes

100,000 items travelling on 200-block pipes take 101 microseconds per tick. One object per item takes 261 microseconds. On 20-block pipes the two are about equal.

## A real server

Velox is a server mod built on Celeris. On a server with Minecraft 1.21.1 and 20,000 dropped items, Velox takes 28.0 ms per tick while the items fall and 21.3 ms once they rest. Vanilla NeoForge takes 493.0 ms and 260.4 ms. That is 18 and 12 times faster, and it takes the server from 2 to 4 ticks per second to a full 20 (median of three runs on 2026-10-30 on a shared GitHub runner). Velox with the Java kernel takes 28.5 ms and 21.5 ms, and with FFM 31.2 ms and 22.9 ms: the three engines tie, and the gain comes from Velox batching the item movement, not from native code.

![Compression benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/compression.png)
![Physics benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/physics.png)
![Broadphase benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/broadphase.png)
![Queue benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/queues.png)
