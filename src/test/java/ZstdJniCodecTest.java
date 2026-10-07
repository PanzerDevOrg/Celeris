import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.PacketPipeline;
import com.panzer.mods.celeris.core.memory.ZstdJniTestAccess;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native zstd over JNI on the Unsafe backend: what a Java 21 player gets
 * without any launch flag. Skipped where the build has no libzstd for this
 * platform (natives are optional in local builds; CI builds them all).
 */
@SuppressWarnings("unused")
class ZstdJniCodecTest {

    private static CompressionCodec codec() {
        assumeTrue(ZstdJniTestAccess.isAvailable(), "no bundled libzstd for this platform");
        return ZstdJniTestAccess.create();
    }

    private static byte[] sample(int size, boolean compressible, long seed) {
        byte[] data = new byte[size];
        Random rng = new Random(seed);
        if (compressible) {
            for (int i = 0; i < size; i++) {
                data[i] = (byte) ((i % 97) < 60 ? (i / 13) % 7 : rng.nextInt(256));
            }
        } else {
            rng.nextBytes(data);
        }
        return data;
    }

    private static byte[] roundTrip(CompressionCodec codec, MemoryBackend backend, byte[] original, int level) {
        long bound = codec.compressBound(original.length);
        long src = backend.allocate(Math.max(1, original.length), 8L);
        long packed = backend.allocate(bound, 8L);
        long out = backend.allocate(Math.max(1, original.length), 8L);
        try {
            backend.copyFromHeap(src, 0L, original, 0, original.length);
            long size = codec.compress(backend, packed, bound, src, 0L, original.length, level);
            assertTrue(size > 0 && size <= bound, "compressed size " + size + " outside (0, " + bound + "]");
            long restored = codec.decompress(backend, out, original.length, packed, 0L, size);
            assertEquals(original.length, restored);
            byte[] result = new byte[original.length];
            backend.copyToHeap(out, 0L, result, 0, result.length);
            return result;
        } finally {
            backend.free(src);
            backend.free(packed);
            backend.free(out);
        }
    }

    @Test
    void roundTripsEverySizeAndLevel() {
        CompressionCodec codec = codec();
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            int[] sizes = {0, 1, 255, 4096, 36 * 1024, 1 << 20};
            for (int size : sizes) {
                for (boolean compressible : new boolean[]{true, false}) {
                    for (int level : new int[]{-1, 1, 3, 9}) {
                        byte[] original = sample(size, compressible, size * 31L + level);
                        assertArrayEquals(original, roundTrip(codec, backend, original, level),
                                "size=" + size + " compressible=" + compressible + " level=" + level);
                    }
                }
            }
        }
    }

    @Test
    void compressesCompressibleData() {
        CompressionCodec codec = codec();
        try (MemoryBackend backend = new UnsafeMemoryBackend();
             PacketPipeline pipeline = new PacketPipeline(256, 3, backend, codec)) {
            byte[] data = new byte[64 * 1024];
            Arrays.fill(data, (byte) 7);
            long src = backend.allocate(data.length, 8L);
            backend.copyFromHeap(src, 0L, data, 0, data.length);
            PacketPipeline.EncodedFrame frame = pipeline.encodeOutbound(src, 0L, data.length);
            assertTrue(frame.compressed());
            assertTrue(frame.size() < 100, "64 KiB of one byte should shrink to almost nothing, got " + frame.size());
            backend.free(src);
        }
    }

    @Test
    void sharedAcrossThreadsWithOverflowingPool() throws Exception {
        assumeTrue(ZstdJniTestAccess.isAvailable(), "no bundled libzstd for this platform");
        // Pool of 2 under 8 threads: exercises reuse, creation on empty and freeing on full.
        CompressionCodec codec = ZstdJniTestAccess.create(2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    Random rng = new Random(thread);
                    for (int i = 0; i < 300; i++) {
                        byte[] original = sample(1 + rng.nextInt(40_000), rng.nextBoolean(), rng.nextLong());
                        assertArrayEquals(original, roundTripLocked(codec, backend, original),
                                "thread " + thread + " op " + i);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Allocation under the backend's lock (its contract), compression outside it, concurrently. */
    private static byte[] roundTripLocked(CompressionCodec codec, MemoryBackend backend, byte[] original) {
        long bound = codec.compressBound(original.length);
        long src, packed, out;
        synchronized (backend) {
            src = backend.allocate(original.length, 8L);
            packed = backend.allocate(bound, 8L);
            out = backend.allocate(original.length, 8L);
        }
        try {
            backend.copyFromHeap(src, 0L, original, 0, original.length);
            long size = codec.compress(backend, packed, bound, src, 0L, original.length, 3);
            long restored = codec.decompress(backend, out, original.length, packed, 0L, size);
            assertEquals(original.length, restored);
            byte[] result = new byte[original.length];
            backend.copyToHeap(out, 0L, result, 0, result.length);
            return result;
        } finally {
            synchronized (backend) {
                backend.free(src);
                backend.free(packed);
                backend.free(out);
            }
        }
    }

    @Test
    void corruptInputThrowsAndCodecStaysUsable() {
        CompressionCodec codec = codec();
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            byte[] garbage = sample(1000, false, 99);
            long src = backend.allocate(garbage.length, 8L);
            long out = backend.allocate(4096, 8L);
            backend.copyFromHeap(src, 0L, garbage, 0, garbage.length);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> codec.decompress(backend, out, 4096, src, 0L, garbage.length));
            assertTrue(e.getMessage().contains("zstd error"), e.getMessage());
            backend.free(src);
            backend.free(out);

            byte[] original = sample(10_000, true, 5);
            assertArrayEquals(original, roundTrip(codec, backend, original, 3), "codec unusable after an error");
        }
    }

    @Test
    void destinationTooSmallThrows() {
        CompressionCodec codec = codec();
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            byte[] original = sample(50_000, false, 3);
            long src = backend.allocate(original.length, 8L);
            long dst = backend.allocate(100, 8L);
            backend.copyFromHeap(src, 0L, original, 0, original.length);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> codec.compress(backend, dst, 100, src, 0L, original.length, 3));
            assertTrue(e.getMessage().contains("too small"), e.getMessage());
            backend.free(src);
            backend.free(dst);
        }
    }

    @Test
    void rangesOutsideTheAllocationNeverReachNativeCode() {
        CompressionCodec codec = codec();
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            long src = backend.allocate(1000, 8L);
            long dst = backend.allocate(100, 8L);
            // Claims 2000 bytes of room in a 100-byte allocation.
            assertThrows(IndexOutOfBoundsException.class,
                    () -> codec.compress(backend, dst, 2000, src, 0L, 1000, 3));
            // Source range past the end of its allocation.
            assertThrows(IndexOutOfBoundsException.class,
                    () -> codec.compress(backend, dst, 100, src, 500L, 600, 3));
            backend.free(src);
            backend.free(dst);
        }
    }

    @Test
    void compressBoundMatchesZstd() {
        CompressionCodec codec = codec();
        // ZSTD_COMPRESSBOUND reference values (zstd.h).
        assertEquals(64, codec.compressBound(0));
        assertEquals(1000 + 3 + 63, codec.compressBound(1000));
        assertEquals((128 << 10) + 512, codec.compressBound(128 << 10));
        assertEquals((1 << 20) + 4096, codec.compressBound(1 << 20));
    }
}
