# Performance

This page gives the main numbers. The [benchmark page](Benchmarks) has every table, the method, and the costs.

The numbers come from JMH benchmarks, run in October 2026 on a shared 4 core cloud machine with Java 21, with Celeris 0.2.3 (the physics kernel has since become 7 to 20 percent slower because it now checks every item for impossible values). The engine comparison in the [Benchmarks](Benchmarks) page can be reproduced with `./gradlew benchmarkTest`. Your numbers will differ. Read differences under about 20 percent as a tie. The ratios are what matter.

## Compression

| Job | Celeris | Java's built-in code | Result |
|---|---|---|---|
| Compress 38 KiB of mod data | Native zstd: 542 MiB per second | Java Deflater: 42 MiB per second | 12.8 times faster, for almost the same size |
| Decompress the same data | 1,296 MiB per second | Java Inflater: 417 MiB per second | 3.1 times faster |

Celeris is about as fast as zstd-jni, the usual Java zstd library. Its jar is about 2 MB, against 7.4 MB for zstd-jni.

## Dropped items

- 32,768 dropped items moved in 0.88 ms per tick, on 3 threads, with the native kernel. A game tick lasts 50 ms at 20 ticks per second, so this is under 2 percent of a tick.
- The same rules in plain Java, on one thread, take 4.72 ms. That is 5.3 times longer.
- Finding the items that touch each other in a pile of 16,384 takes 0.24 ms. Before, the same search could take close to a second.

## Passing results between threads

- One worker thread handing results to the game thread: 39.5 million per second. JCTools, a well known lock-free queue library, does 8.0 million per second. That is 4.9 times faster.
- With two or three workers, Celeris is still 1.3 to 1.5 times faster than JCTools.

## Items on pipes

100,000 items travelling on 200-block pipes take 101 microseconds per tick. One object per item takes 261 microseconds. On 20-block pipes the two are about equal.

## A real server

Velox is a server mod built on Celeris. On a server with Minecraft 1.21.1 and 20,000 dropped items, Velox takes 11.9 ms per tick. Vanilla NeoForge takes 139.9 ms. That is 11.8 times faster, and it takes the server from 3 ticks per second to a full 20.

![Compression benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/compression.png)
![Physics benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/physics.png)
![Broadphase benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/broadphase.png)
![Queue benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/queues.png)
