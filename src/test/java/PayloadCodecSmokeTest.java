import com.panzer.mods.celeris.network.CompressedPayload;
import com.panzer.mods.celeris.network.PayloadCompression;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
class PayloadCodecSmokeTest {

    @Test
    void smallPayloadPassesThroughUncompressed() {
        byte[] small = new byte[50];
        new Random(1).nextBytes(small);

        CompressedPayload payload = PayloadCompression.compress(small);
        assertFalse(payload.compressed(), "Expected no compression below threshold");

        byte[] result = PayloadCompression.decompress(payload);
        assertArrayEquals(small, result, "Small payload round-trip mismatch");
    }

    @Test
    void largeRepetitivePayloadCompresses() {
        byte[] large = new byte[20_000];
        Arrays.fill(large, (byte) 42);

        CompressedPayload payload = PayloadCompression.compress(large);
        assertTrue(payload.compressed(), "Expected compression for large repetitive payload");

        byte[] result = PayloadCompression.decompress(payload);
        assertArrayEquals(large, result, "Large payload round-trip mismatch");
    }

    @Test
    void randomPayloadsRoundTripAcrossSizes() {
        Random rng = new Random(99);
        for (int i = 0; i < 500; i++) {
            int size = 10 + rng.nextInt(30_000);
            byte[] data = new byte[size];
            if (i % 2 == 0) {
                Arrays.fill(data, (byte) (i % 128));
            } else {
                rng.nextBytes(data);
            }

            CompressedPayload payload = PayloadCompression.compress(data);
            byte[] result = PayloadCompression.decompress(payload);

            assertArrayEquals(data, result, "Mismatch at iteration " + i + " size=" + size);
        }
    }
}
