import com.panzer.mods.celeris.config.CelerisSettings;
import com.panzer.mods.celeris.network.CompressedPayload;
import com.panzer.mods.celeris.network.PayloadCompression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The user's compression level ({@link CelerisSettings}) drives the default
 * {@link PayloadCompression} overloads: 0 sends payloads as they are, 1-9 are
 * deflate's levels, and every level stays readable by any peer.
 */
@SuppressWarnings("unused")
class CompressionLevelSettingTest {

    @AfterEach
    void reset() {
        CelerisSettings.resetToDefaults();
    }

    /** Text-like data: compressible, but differently at each level. */
    private static byte[] data() {
        Random random = new Random(42);
        String[] words = {"chunk", "section", "block", "entity", "item", "pipe", "network", "graph", "node", "edge"};
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 64_000) {
            sb.append(words[random.nextInt(words.length)]).append(random.nextInt(1000)).append(' ');
        }
        return sb.toString().getBytes();
    }

    private static byte[] deflate(byte[] data, int level) {
        Deflater d = new Deflater(level);
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length * 2];
        int n = d.deflate(out);
        d.end();
        return Arrays.copyOf(out, n);
    }

    @Test
    void defaultIsDeflateFastest() {
        assertEquals(1, CelerisSettings.compressionLevel());
        byte[] original = data();
        CompressedPayload payload = PayloadCompression.compress(original);
        assertTrue(payload.compressed());
        assertArrayEquals(deflate(original, 1), payload.data());
    }

    @Test
    void eachLevelProducesThatDeflateLevel() {
        byte[] original = data();
        for (int level = 1; level <= 9; level++) {
            int l = level;
            CelerisSettings.compressionLevelSource(() -> l);
            CompressedPayload payload = PayloadCompression.compress(original);
            assertTrue(payload.compressed(), "level " + level);
            assertArrayEquals(deflate(original, level), payload.data(), "level " + level);
            assertArrayEquals(original, PayloadCompression.decompress(payload), "level " + level);
            CompressedPayload owned = PayloadCompression.compressOwned(original.clone());
            assertArrayEquals(payload.data(), owned.data(), "owned, level " + level);
        }
    }

    @Test
    void levelNineIsSmallerThanLevelOne() {
        byte[] original = data();
        CelerisSettings.compressionLevelSource(() -> 1);
        int fast = PayloadCompression.compress(original).data().length;
        CelerisSettings.compressionLevelSource(() -> 9);
        int small = PayloadCompression.compress(original).data().length;
        assertTrue(small < fast, "level 9 " + small + " B vs level 1 " + fast + " B");
    }

    @Test
    void levelZeroSendsUncompressed() {
        CelerisSettings.compressionLevelSource(() -> 0);
        byte[] original = data();
        CompressedPayload payload = PayloadCompression.compress(original);
        assertFalse(payload.compressed());
        assertArrayEquals(original, payload.data());
        assertNotSame(original, payload.data());
        assertArrayEquals(original, PayloadCompression.decompress(payload));

        CompressedPayload owned = PayloadCompression.compressOwned(original);
        assertFalse(owned.compressed());
        assertSame(original, owned.data());
    }

    @Test
    void outOfRangeValuesAreClamped() {
        CelerisSettings.compressionLevelSource(() -> -5);
        assertEquals(0, CelerisSettings.compressionLevel());
        CelerisSettings.compressionLevelSource(() -> 22);
        assertEquals(9, CelerisSettings.compressionLevel());
        assertArrayEquals(deflate(data(), 9), PayloadCompression.compress(data()).data());
    }

    @Test
    void explicitLevelOverloadIgnoresTheSetting() {
        CelerisSettings.compressionLevelSource(() -> 0);
        CompressedPayload payload = PayloadCompression.compress(data(), 256, 3);
        assertTrue(payload.compressed());
        assertArrayEquals(deflate(data(), 1), payload.data());
    }

    @Test
    void nullSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CelerisSettings.compressionLevelSource(null));
    }
}
