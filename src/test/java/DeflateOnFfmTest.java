import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.DeflateCompressionCodecTestAccess;
import com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deflate on FFM memory. Kept apart from {@code DeflatePathsTest} because it
 * needs FFM in the test JVM. Guards against handing zlib ByteBuffer views of
 * shared-arena segments, which the JDK refuses.
 */
@SuppressWarnings("unused")
class DeflateOnFfmTest {

    @Test
    void roundTripsOnFfmMemory() {
        CompressionCodec codec = DeflateCompressionCodecTestAccess.create();
        try (MemoryBackend backend = new FfmMemoryBackend()) {
            byte[] original = new byte[100_000];
            for (int i = 0; i < original.length; i++) {
                original[i] = (byte) (i / 50);
            }
            long bound = codec.compressBound(original.length);
            long src = backend.allocate(original.length, 8L);
            long packed = backend.allocate(bound, 8L);
            long out = backend.allocate(original.length, 8L);
            backend.copyFromHeap(src, 0L, original, 0, original.length);
            long size = codec.compress(backend, packed, bound, src, 0L, original.length, 3);
            assertTrue(size < original.length);
            assertEquals(original.length, codec.decompress(backend, out, original.length, packed, 0L, size));
            byte[] result = new byte[original.length];
            backend.copyToHeap(out, 0L, result, 0, result.length);
            assertArrayEquals(original, result);
        }
    }
}
