# Celeris benchmarks

How Celeris's building blocks compare with what a mod would otherwise use: the JDK's own classes and the best-known
Java libraries for the same job. Every number below comes from the code in [`benchmarks/`](../benchmarks) and the raw
results in [`benchmarks/results/`](../benchmarks/results); `benchmarks/run.sh` reproduces them and redraws the charts.

**Machine:** 4 vCPU Intel Xeon @ 2.1 GHz (shared cloud VM, AVX2), Linux, OpenJDK 21. **Harness:** JMH 1.37, 1 fork,
3 × 1 s warm-up, 5 × 1 s measurement (± is JMH's 99.9 % error). A shared VM is noisy: read differences under ~20 % as
a tie. Your numbers will differ; the ratios are what matter.

## Summary

| Job | Celeris | Compared with | Result |
|---|---|---|---|
| Compressing mod data (38 KiB) | native zstd, 620 MiB/s, ratio 5.5× | Java `Deflater` level 6 (what Minecraft uses), 51 MiB/s, ratio 5.8× | **12× faster** for nearly the same size |
| Decompressing it | 1,636 MiB/s | Java `Inflater`, 521 MiB/s | **3.1× faster** |
| Same, vs zstd-jni 1.5.7 | 620 / 1,636 MiB/s | 622 / 1,609 MiB/s | **tie**: same zstd 1.5.7 underneath, in a 2 MB jar instead of a 7.4 MB one |
| Moving 10,000 dropped items one tick | 0.66 ms (native kernel) | 1.07 ms (same rules in Java, item by item) | **1.6× faster** |
| 32,768 items on 3 threads | 0.87 ms | 3.40 ms (Java, one thread) | **3.9× faster**, under 2 % of a 50 ms tick |
| One worker handing results to the game thread | 42.8 M/s | JCTools `MpscArrayQueue` 6.7 M/s, `ArrayBlockingQueue` 5.6 M/s | **6.4× faster** than JCTools |
| Two or three workers at once | 7.6 / 5.8 M/s | JCTools 6.0 / 4.5, `ArrayBlockingQueue` 7.9 / 6.1 | **level** with the JDK's lock-based queue, ahead of JCTools |

Where Celeris is not the fastest, this page says so: LZ4 compresses 2.4× faster than zstd, at the price of output about
40 % larger (3.9× instead of 5.5×), and with several threads publishing at once on this 4-core machine the plain
`ArrayBlockingQueue` keeps up with Celeris's lock-free ring.

## Compression

![Compression throughput](media/benchmarks/compression.png)

Synthetic chunk-section data (NBT-like palettes and block states, see `ChunkData.java`) at three sizes: one network
packet (3 KiB), one chunk (38 KiB) and a region-sized blob (1 MiB). Time per operation in µs, lower is better:

| | 3 KiB | 38 KiB | 1 MiB |
|---|---|---|---|
| **Celeris zstd, compress** (JNI binding, Java 21 without flags) | **10.2 ± 3.0** | **59.8 ± 7.9** | **2,644 ± 715** |
| Celeris zstd, compress (FFM binding, `--enable-preview` / Java 25) | 10.0 ± 2.2 | 59.4 ± 21.1 | 2,628 ± 454 |
| zstd-jni 1.5.7-6, compress (`byte[]`) | 9.7 ± 4.7 | 59.6 ± 18.0 | 2,591 ± 512 |
| zstd-jni 1.5.7-6, compress (direct buffers) | 10.2 ± 1.4 | 60.7 ± 13.8 | 2,784 ± 442 |
| LZ4 (lz4-java 1.8.0, fast), compress | 2.7 ± 0.6 | 25.5 ± 8.8 | 1,459 ± 295 |
| Java `Deflater` level 1, compress | 15.9 ± 5.0 | 238.8 ± 48.4 | 7,200 ± 3,451 |
| Java `Deflater` level 6, compress | 51.3 ± 14.0 | 732.8 ± 235.3 | 20,831 ± 3,996 |
| **Celeris zstd, decompress** (JNI) | **4.6 ± 1.3** | **22.7 ± 11.2** | **830 ± 86** |
| Celeris zstd, decompress (FFM) | 4.7 ± 2.1 | 23.5 ± 8.2 | 762 ± 172 |
| zstd-jni, decompress (`byte[]`) | 4.9 ± 1.5 | 23.1 ± 1.8 | 821 ± 185 |
| zstd-jni, decompress (direct) | 4.5 ± 1.7 | 23.8 ± 8.4 | 850 ± 148 |
| LZ4, decompress | 1.6 ± 0.4 | 12.6 ± 2.2 | 377 ± 145 |
| Java `Inflater` | 7.3 ± 2.1 | 71.2 ± 14.8 | 2,464 ± 527 |

Compression ratio (original ÷ compressed):

| | 3 KiB | 38 KiB | 1 MiB |
|---|---|---|---|
| zstd level 3 (Celeris and zstd-jni) | 4.43 | 5.51 | 6.38 |
| Deflater level 6 | 4.81 | 5.83 | 6.30 |
| Deflater level 1 | 4.22 | 5.07 | 5.37 |
| LZ4 | 2.95 | 3.88 | 4.35 |

What this means: zstd gives deflate-level-6 sizes at better-than-level-1 speed. Celeris's own binding costs nothing
over zstd-jni's (both are one native call into the same library, with pooled contexts), and it ships inside a 2 MB jar
that also carries the physics kernel and every platform's natives. Packets between players still use deflate, so
clients and servers on different Java versions always agree; zstd is for mods' own data (saves, caches, bulk
transfers).

## Batch physics

![Physics time per tick](media/benchmarks/physics.png)

One game tick of N dropped items in a random 48×32×48 block world: gravity, drag, collision with blocks and ground
friction, following vanilla's item movement rules. The items are thrown again every 40 ticks so the batch never just
rests. "Java" is Celeris's pure-Java kernel, which applies the rules one item at a time the way vanilla does; it does
not include the rest of vanilla's per-entity cost (entity ticking, events, tracking), so vanilla itself is slower
still. Time per tick in µs:

| Items | Java kernel | Native kernel (AVX2) | Speed-up |
|---|---|---|---|
| 1,000 | 104.6 ± 46.9 | 67.4 ± 20.1 | 1.6× |
| 10,000 | 1,071.8 ± 307.1 | 656.7 ± 208.6 | 1.6× |
| 32,768 | 3,398.9 ± 1,340.3 | 2,462.8 ± 756.3 | 1.4× |
| 32,768, 3 worker threads | 1,193.1 ± 256.8 | 868.1 ± 146.7 | 1.4× (3.9× vs one Java thread) |

The native kernel is picked automatically when the CPU and JVM allow it (AVX2, AVX-512 or NEON builds ship in the
jar); otherwise the Java kernel runs with identical results.

## Handing results to the game thread

![Queue throughput](media/benchmarks/queues.png)

The pattern Celeris is built around: worker threads produce 8-byte results (tickets, handles, packed positions) and
the game thread consumes them. Each round moves 20 million values through a 65,536-slot queue; producers spin when it
is full, the consumer when it is empty. Median of 9 rounds, in million values per second:

| Queue | 1 producer | 2 producers | 3 producers |
|---|---|---|---|
| **Celeris `MpscRingBuffer` (0.2.3)** | **42.8** | 7.6 | 5.8 |
| Celeris `MpscRingBuffer` (0.2.2) | 10.9 | 5.5 | 4.2 |
| JCTools `MpscArrayQueue` 4.0.5 | 6.7 | 6.0 | 4.5 |
| `ArrayBlockingQueue` (JDK) | 5.6 | 7.9 | 6.1 |
| `ConcurrentLinkedQueue` (JDK) | 7.2 | 4.2 | 3.1 |

Celeris stores the values themselves in off-heap slots; the object queues box each one into a `Long`, which is part of
their cost and is what a mod using them for primitive results pays too. 0.2.3 made the ring 4× faster with one
producer: producers no longer read the consumer's position on every publish (they keep a cached limit), and each
slot's state word now sits next to its payload, so a hand-off touches one cache line instead of two. With 3 producers
plus the consumer, this 4-core VM has every core busy, and all queues end up limited by the same contention.
