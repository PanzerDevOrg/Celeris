import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.DeflateCompressionCodecTestAccess;
import com.panzer.mods.celeris.core.memory.backend.HeapMemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deflate codec's two data paths: zlib working on the backend's memory
 * through ByteBuffer views (heap backend here, FFM in game) and through
 * per-thread scratch arrays (Unsafe), plus its strictness on bad input.
 * The output is a standard zlib stream either way.
 */
@SuppressWarnings("unused")
class DeflatePathsTest {

    private static byte[] sample(int size, long seed) {
        byte[] data = new byte[size];
        Random rng = new Random(seed);
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 5 == 0 ? rng.nextInt(256) : i / 64);
        }
        return data;
    }

    private static void roundTrips(MemoryBackend backend) throws Exception {
        CompressionCodec codec = DeflateCompressionCodecTestAccess.create();
        for (int size : new int[]{0, 1, 300, 70_000, 1 << 20}) {
            byte[] original = sample(size, size);
            long bound = codec.compressBound(size);
            long src = backend.allocate(Math.max(1, size), 8L);
            long packed = backend.allocate(bound, 8L);
            long out = backend.allocate(Math.max(1, size), 8L);
            try {
                backend.copyFromHeap(src, 0L, original, 0, size);
                long packedSize = codec.compress(backend, packed, bound, src, 0L, size, 3);

                // A plain JDK Inflater reads it: same format as before.
                byte[] wire = new byte[(int) packedSize];
                backend.copyToHeap(packed, 0L, wire, 0, wire.length);
                Inflater plain = new Inflater();
                plain.setInput(wire);
                byte[] check = new byte[size];
                int n = size == 0 ? 0 : plain.inflate(check);
                plain.end();
                assertEquals(size, n);
                assertArrayEquals(original, check);

                assertEquals(size, codec.decompress(backend, out, Math.max(1, size), packed, 0L, packedSize));
                byte[] result = new byte[size];
                backend.copyToHeap(out, 0L, result, 0, size);
                assertArrayEquals(original, result, backend.name() + " size=" + size);
            } finally {
                backend.free(src);
                backend.free(packed);
                backend.free(out);
            }
        }
    }

    @Test
    void roundTripsThroughByteBufferViews() throws Exception {
        try (MemoryBackend backend = new HeapMemoryBackend()) {
            roundTrips(backend);
        }
    }

    @Test
    void roundTripsThroughScratchArrays() throws Exception {
        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            roundTrips(backend);
        }
    }

    @Test
    void outputLargerThanTheDestinationIsAnError() {
        for (MemoryBackend backend : new MemoryBackend[]{new HeapMemoryBackend(), new UnsafeMemoryBackend()}) {
            try (backend) {
                CompressionCodec codec = DeflateCompressionCodecTestAccess.create();
                byte[] original = sample(10_000, 1);
                long bound = codec.compressBound(original.length);
                long src = backend.allocate(original.length, 8L);
                long packed = backend.allocate(bound, 8L);
                long small = backend.allocate(5_000, 8L);
                backend.copyFromHeap(src, 0L, original, 0, original.length);
                long packedSize = codec.compress(backend, packed, bound, src, 0L, original.length, 3);
                assertThrows(IllegalStateException.class,
                        () -> codec.decompress(backend, small, 5_000, packed, 0L, packedSize), backend.name());
                // Cut-off input: the stream never finishes.
                assertThrows(IllegalStateException.class,
                        () -> codec.decompress(backend, src, original.length, packed, 0L, packedSize / 2), backend.name());
            }
        }
    }

    @Test
    void readsStreamsFromAnyDeflater() {
        // What earlier Celeris versions (a fresh Deflater per call) produced.
        byte[] original = sample(50_000, 7);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(original);
        deflater.finish();
        byte[] buf = new byte[original.length + 1024];
        int n = deflater.deflate(buf);
        deflater.end();

        try (MemoryBackend backend = new UnsafeMemoryBackend()) {
            CompressionCodec codec = DeflateCompressionCodecTestAccess.create();
            long src = backend.allocate(n, 8L);
            long out = backend.allocate(original.length, 8L);
            backend.copyFromHeap(src, 0L, buf, 0, n);
            assertEquals(original.length, codec.decompress(backend, out, original.length, src, 0L, n));
            byte[] result = new byte[original.length];
            backend.copyToHeap(out, 0L, result, 0, result.length);
            assertArrayEquals(original, result);
        }
    }
}
