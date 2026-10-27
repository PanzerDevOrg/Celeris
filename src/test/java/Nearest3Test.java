import net.bichal.mods.celeris.core.spatial.Nearest3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Nearest3Test {

    /** The packed-long loop of the vanilla aquifer search, kept as the oracle. */
    static long[] reference(long[] packed, int x, int y, int z) {
        return reference(packed, x, y, z, new long[6]);
    }

    static long[] reference(long[] packed, int x, int y, int z, long[] out) {
        int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE;
        long p1 = 0, p2 = 0, p3 = 0;
        for (long p : packed) {
            int dx = (int) (p >> 38) - x;
            int dy = (int) (p << 52 >> 52) - y;
            int dz = (int) (p << 26 >> 38) - z;
            int d = dx * dx + dy * dy + dz * dz;
            if (d1 >= d) { p3 = p2; p2 = p1; p1 = p; d3 = d2; d2 = d1; d1 = d; }
            else if (d2 >= d) { p3 = p2; p2 = p; d3 = d2; d2 = d; }
            else if (d3 >= d) { p3 = p; d3 = d; }
        }
        out[0] = p1; out[1] = p2; out[2] = p3; out[3] = d1; out[4] = d2; out[5] = d3;
        return out;
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }

    @Test
    void matchesReferenceIncludingTies() {
        Random rnd = new Random(7);
        for (int iter = 0; iter < 200_000; iter++) {
            int n = 12;
            int[] xs = new int[n], ys = new int[n], zs = new int[n];
            long[] packed = new long[n];
            // small ranges make distance ties frequent
            int range = iter % 3 == 0 ? 3 : 40;
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextInt(range) - range / 2 + 100_000;
                ys[i] = rnd.nextInt(range) - range / 2;
                zs[i] = rnd.nextInt(range) - range / 2 - 30_000;
                packed[i] = pack(xs[i], ys[i], zs[i]);
            }
            int x = rnd.nextInt(range) - range / 2 + 100_000;
            int y = rnd.nextInt(range) - range / 2;
            int z = rnd.nextInt(range) - range / 2 - 30_000;
            long[] ref = reference(packed, x, y, z);
            int r = Nearest3.search(xs, ys, zs, n, x, y, z);
            int[] idx = {Nearest3.first(r), Nearest3.second(r), Nearest3.third(r)};
            for (int k = 0; k < 3; k++) {
                assertEquals(ref[k], packed[idx[k]], "iter " + iter + " rank " + k);
                assertEquals((int) ref[3 + k], Nearest3.distanceSq(xs, ys, zs, idx[k], x, y, z));
            }
        }
    }
}
