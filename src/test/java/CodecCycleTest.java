import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.DeflateCompressionCodecTestAccess;
import com.panzer.mods.celeris.core.memory.PacketPipeline;
import com.panzer.mods.celeris.core.memory.backend.HeapMemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Round-trips random payloads through {@link PacketPipeline} on the
 * pure-Java {@link HeapMemoryBackend} + Deflate codec path -- i.e.
 * exactly what a JVM without usable FFM falls back to. This is the test
 * that actually proves compat mode works end-to-end, not just that it
 * compiles.
 * <p>
 * The FFM/zstd path is exercised separately wherever a real {@code
 * FfmMemoryBackend} is constructed (e.g. {@code PayloadCodecSmokeTest}) --
 * not duplicated here, since this module can't assume FFM is available in
 * the test JVM either.
 */
@SuppressWarnings("unused")
class CodecCycleTest {

    @Test
    void twoThousandRandomCyclesRoundTripOnCompatBackend() {
        MemoryBackend backend = new HeapMemoryBackend();
        CompressionCodec codec = DeflateCompressionCodecTestAccess.create();

        try (backend; PacketPipeline pipeline = new PacketPipeline(256, 3, backend, codec)) {
            Random rng = new Random(7);
            for (int cycle = 0; cycle < 2000; cycle++) {
                int size = 50 + rng.nextInt(20_000);
                byte[] original = new byte[size];
                if (cycle % 3 == 0) {
                    Arrays.fill(original, (byte) (cycle % 128));
                } else {
                    rng.nextBytes(original);
                }

                long srcHandle = backend.allocate(size, 8L);
                try {
                    backend.copyFromHeap(srcHandle, 0L, original, 0, size);

                    PacketPipeline.EncodedFrame frame = pipeline.encodeOutbound(srcHandle, 0L, size);

                    byte[] wireBytes = new byte[(int) frame.size()];
                    backend.copyToHeap(frame.handle(), frame.offset(), wireBytes, 0, wireBytes.length);

                    long wireHandle = backend.allocate(wireBytes.length, 8L);
                    try {
                        backend.copyFromHeap(wireHandle, 0L, wireBytes, 0, wireBytes.length);

                        PacketPipeline.DecodedFrame decoded = pipeline.decodeInbound(
                                wireHandle, 0L, wireBytes.length, frame.compressed(), size);

                        byte[] result = new byte[(int) decoded.size()];
                        backend.copyToHeap(decoded.handle(), decoded.offset(), result, 0, result.length);

                        assertArrayEquals(original, result,
                                "Cycle " + cycle + " mismatch: size=" + size + " compressed=" + frame.compressed());
                    } finally {
                        backend.free(wireHandle);
                    }
                } finally {
                    backend.free(srcHandle);
                }
            }
        }
    }
}
