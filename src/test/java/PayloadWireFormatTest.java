import com.panzer.mods.celeris.network.CompressedPayload;
import com.panzer.mods.celeris.network.PayloadCompression;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network format must not change: payloads are a plain zlib stream that
 * any peer, old or new, can read, and a payload's declared size is enforced.
 */
@SuppressWarnings("unused")
class PayloadWireFormatTest {

    private static byte[] compressible(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i / 32);
        }
        return data;
    }

    @Test
    void newPayloadsAreStandardZlib() throws Exception {
        byte[] original = compressible(20_000);
        CompressedPayload payload = PayloadCompression.compress(original);
        assertTrue(payload.compressed());
        assertEquals(original.length, payload.originalSize());

        Inflater inflater = new Inflater();
        inflater.setInput(payload.data());
        byte[] out = new byte[original.length];
        assertEquals(original.length, inflater.inflate(out));
        assertTrue(inflater.finished());
        inflater.end();
        assertArrayEquals(original, out);
    }

    @Test
    void readsPayloadsFromEarlierVersions() {
        // Earlier versions: new Deflater(1) per payload (zstd level 3 mapped onto deflate 1).
        byte[] original = compressible(30_000);
        Deflater deflater = new Deflater(1);
        deflater.setInput(original);
        deflater.finish();
        byte[] buf = new byte[original.length];
        int n = deflater.deflate(buf);
        deflater.end();
        CompressedPayload old = new CompressedPayload(Arrays.copyOf(buf, n), true, original.length);
        assertArrayEquals(original, PayloadCompression.decompress(old));
    }

    @Test
    void thresholdAndIncompressibleDataStayUncompressed() {
        byte[] small = compressible(100);
        CompressedPayload a = PayloadCompression.compress(small);
        assertFalse(a.compressed());
        assertArrayEquals(small, a.data());
        assertNotSame(small, a.data(), "payload must not alias the caller's array");

        byte[] noise = new byte[5_000];
        new Random(3).nextBytes(noise);
        CompressedPayload b = PayloadCompression.compress(noise);
        assertFalse(b.compressed(), "random bytes do not shrink, so they travel as-is");
        assertArrayEquals(noise, PayloadCompression.decompress(b));

        // An explicit threshold is honoured now.
        assertTrue(PayloadCompression.compress(small, 50, 3).compressed());
    }

    @Test
    void declaredSizeIsEnforced() {
        byte[] original = compressible(10_000);
        CompressedPayload good = PayloadCompression.compress(original);

        CompressedPayload claimsMore = new CompressedPayload(good.data(), true, original.length + 1);
        assertThrows(IllegalStateException.class, () -> PayloadCompression.decompress(claimsMore));

        CompressedPayload claimsLess = new CompressedPayload(good.data(), true, original.length - 1);
        assertThrows(IllegalStateException.class, () -> PayloadCompression.decompress(claimsLess));

        CompressedPayload tooBig = new CompressedPayload(good.data(), true, PayloadCompression.MAX_DECOMPRESSED_SIZE + 1);
        assertThrows(IllegalArgumentException.class, () -> PayloadCompression.decompress(tooBig));

        CompressedPayload garbage = new CompressedPayload(new byte[]{1, 2, 3, 4, 5}, true, 100);
        assertThrows(IllegalStateException.class, () -> PayloadCompression.decompress(garbage));
    }

    @Test
    void manyThreadsShareNothing() throws Exception {
        Thread[] threads = new Thread[6];
        Throwable[] failure = new Throwable[1];
        for (int t = 0; t < threads.length; t++) {
            final int seed = t;
            threads[t] = new Thread(() -> {
                try {
                    Random rng = new Random(seed);
                    for (int i = 0; i < 2_000; i++) {
                        byte[] data = compressible(256 + rng.nextInt(20_000));
                        data[rng.nextInt(data.length)] = (byte) rng.nextInt();
                        assertArrayEquals(data, PayloadCompression.decompress(PayloadCompression.compress(data)));
                    }
                } catch (Throwable e) {
                    synchronized (failure) {
                        failure[0] = e;
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure[0] != null) {
            fail(failure[0]);
        }
    }
}
