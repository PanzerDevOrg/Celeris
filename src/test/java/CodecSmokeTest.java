import com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend;
import com.panzer.mods.celeris.core.memory.PacketPipeline;
import com.panzer.mods.celeris.core.memory.ZstdCompressionCodecTestAccess;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the FFM/zstd path specifically, using a real {@link
 * FfmMemoryBackend} directly rather than going through {@code
 * CelerisRuntime} -- this test asserts the fast path itself works, so it
 * must not silently pass by falling back to compat mode on a test JVM
 * that happens to lack FFM. If FFM isn't available here, that's a test
 * environment problem to fix (add --enable-preview to the test JVM args),
 * not something to paper over.
 * <p>
 * Compat-mode's own round-trip correctness is covered separately by
 * {@code CodecCycleTest}, against {@code HeapMemoryBackend} + Deflate.
 */
@SuppressWarnings({"unused"})
class CodecSmokeTest {

    @Test
    void rawCodecRoundTrip() {
        try (FfmMemoryBackend backend = new FfmMemoryBackend()) {
            byte[] original = new byte[50_000];
            new Random(42).nextBytes(original);
            for (int i = 0; i < original.length; i += 8) {
                original[i] = 65;
            }

            long srcHandle = backend.allocate(original.length, 8L);
            backend.copyFromHeap(srcHandle, 0L, original, 0, original.length);

            var codec = ZstdCompressionCodecTestAccess.create();
            long bound = codec.compressBound(original.length);
            long compressedHandle = backend.allocate(bound, 8L);
            long compressedSize = codec.compress(backend, compressedHandle, bound, srcHandle, 0L, original.length, 3);

            assertTrue(compressedSize < original.length, "Expected compression to reduce size for repetitive data");

            long decompressedHandle = backend.allocate(original.length, 8L);
            long decompressedSize = codec.decompress(backend, decompressedHandle, original.length, compressedHandle, 0L, compressedSize);

            assertEquals(original.length, decompressedSize, "Size mismatch after decompression");

            byte[] result = new byte[original.length];
            backend.copyToHeap(decompressedHandle, 0L, result, 0, original.length);

            assertArrayEquals(original, result, "Round-trip data mismatch");
        }
    }

    @Test
    void pipelineBelowThreshold() {
        FfmMemoryBackend backend = new FfmMemoryBackend();
        try (backend; PacketPipeline pipeline = new PacketPipeline(256, 3, backend, ZstdCompressionCodecTestAccess.create())) {
            byte[] small = new byte[50];
            new Random(1).nextBytes(small);

            long srcHandle = backend.allocate(small.length, 8L);
            backend.copyFromHeap(srcHandle, 0L, small, 0, small.length);

            PacketPipeline.EncodedFrame frame = pipeline.encodeOutbound(srcHandle, 0L, small.length);

            assertFalse(frame.compressed(), "Expected no compression below threshold");
            assertEquals(small.length, frame.size(), "Expected passthrough size to match input");
        }
    }

    @Test
    void pipelineAboveThreshold() {
        FfmMemoryBackend backend = new FfmMemoryBackend();
        try (backend; PacketPipeline pipeline = new PacketPipeline(256, 3, backend, ZstdCompressionCodecTestAccess.create())) {
            byte[] large = new byte[10_000];
            Arrays.fill(large, (byte) 7);

            long srcHandle = backend.allocate(large.length, 8L);
            backend.copyFromHeap(srcHandle, 0L, large, 0, large.length);

            PacketPipeline.EncodedFrame frame = pipeline.encodeOutbound(srcHandle, 0L, large.length);

            assertTrue(frame.compressed(), "Expected compression above threshold for highly repetitive data");

            PacketPipeline.DecodedFrame decoded = pipeline.decodeInbound(
                    frame.handle(), frame.offset(), frame.size(), true, large.length);

            byte[] result = new byte[(int) decoded.size()];
            backend.copyToHeap(decoded.handle(), decoded.offset(), result, 0, result.length);

            assertArrayEquals(large, result, "Pipeline round-trip mismatch");
        }
    }
}
