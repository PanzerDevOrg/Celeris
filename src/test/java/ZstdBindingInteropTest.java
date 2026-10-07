import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.ZstdCompressionCodecTestAccess;
import com.panzer.mods.celeris.core.memory.ZstdJniTestAccess;
import com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The FFM and JNI bindings drive the same libzstd, so each must read what the
 * other wrote, and the JNI one must work on FFM memory too (raw addresses come
 * from the backend, not the binding). Needs FFM in the test JVM, like
 * {@code CodecSmokeTest}.
 */
@SuppressWarnings("unused")
class ZstdBindingInteropTest {

    @Test
    void ffmAndJniReadEachOther() {
        assumeTrue(ZstdJniTestAccess.isAvailable(), "no bundled libzstd for this platform");
        CompressionCodec ffm = ZstdCompressionCodecTestAccess.create();
        CompressionCodec jni = ZstdJniTestAccess.create();
        try (MemoryBackend backend = new FfmMemoryBackend()) {
            byte[] original = new byte[200_000];
            Random rng = new Random(11);
            for (int i = 0; i < original.length; i++) {
                original[i] = (byte) (i % 3 == 0 ? rng.nextInt(256) : i / 100);
            }
            assertArrayEquals(original, crossRoundTrip(ffm, jni, backend, original), "FFM -> JNI");
            assertArrayEquals(original, crossRoundTrip(jni, ffm, backend, original), "JNI -> FFM");
        }
    }

    private static byte[] crossRoundTrip(CompressionCodec writer, CompressionCodec reader, MemoryBackend backend, byte[] original) {
        long bound = writer.compressBound(original.length);
        long src = backend.allocate(original.length, 8L);
        long packed = backend.allocate(bound, 8L);
        long out = backend.allocate(original.length, 8L);
        try {
            backend.copyFromHeap(src, 0L, original, 0, original.length);
            long size = writer.compress(backend, packed, bound, src, 0L, original.length, 3);
            assertEquals(original.length, reader.decompress(backend, out, original.length, packed, 0L, size));
            byte[] result = new byte[original.length];
            backend.copyToHeap(out, 0L, result, 0, result.length);
            return result;
        } finally {
            backend.free(src);
            backend.free(packed);
            backend.free(out);
        }
    }
}
