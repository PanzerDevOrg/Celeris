# Performance

Each building block, measured against what a mod would otherwise use (JMH, Java 21, a 4-core machine; full tables,
method and the costs too in [the benchmarks page](./docs/benchmarks.md)):

- **12.8 times faster compression** than Java's built-in deflate (what Minecraft uses) for nearly the same size, and
  **3.1 times faster decompression**. As fast as zstd-jni, the usual Java zstd library, in a jar a quarter of its size.
- **32,768 dropped items moved in 0.88 ms** per tick on 3 threads with the native kernel, 5.3 times faster than the
  same rules in Java on one thread and under 2% of a tick.
- **Item piles no longer stall**: finding the touching items in a pile of 16,384 takes 0.24 ms instead of up to a
  second.
- **4.9 times faster hand-off** from one worker thread to the game thread than JCTools, the go-to lock-free queue
  library, and still ahead with two or three workers.

![Compression benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/compression.png)
![Physics benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/physics.png)
![Broadphase benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/broadphase.png)
![Queue benchmark](https://raw.githubusercontent.com/bichal/Celeris/main/docs/media/benchmarks/queues.png)

Full tables: [benchmarks](https://github.com/bichal/Celeris/blob/main/docs/benchmarks.md).

[Back to Home](Home.md)
