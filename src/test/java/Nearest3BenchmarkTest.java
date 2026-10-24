import com.panzer.mods.celeris.core.spatial.Nearest3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/** Nearest-3-of-12 search: flat int[] kernel vs the packed-long loop. Run with {@code ./gradlew benchmarkTest}. */
@Tag("benchmark")
class Nearest3BenchmarkTest {

    private static final int QUERIES = 1 << 16;
    private static final int WARMUP_TRIALS = 5;
    private static final int MEASURED_TRIALS = 9;
    private static volatile long sink;

    @Test
    void compare() {
        Random rnd = new Random(1);
        int[] xs = new int[12], ys = new int[12], zs = new int[12];
        long[] packed = new long[12];
        for (int i = 0; i < 12; i++) {
            xs[i] = (i >> 2) * 16 + rnd.nextInt(10);
            ys[i] = (i % 3) * 12 + rnd.nextInt(9);
            zs[i] = (i & 1) * 16 + rnd.nextInt(10);
            packed[i] = Nearest3Test.pack(xs[i], ys[i], zs[i]);
        }
        int[] qx = new int[QUERIES], qy = new int[QUERIES], qz = new int[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            qx[i] = rnd.nextInt(32);
            qy[i] = rnd.nextInt(36);
            qz[i] = rnd.nextInt(32);
        }
        long[] out = new long[6];
        double[] naive = new double[MEASURED_TRIALS], kernel = new double[MEASURED_TRIALS];
        for (int t = -WARMUP_TRIALS; t < MEASURED_TRIALS; t++) {
            long t0 = System.nanoTime();
            long acc = 0;
            for (int i = 0; i < QUERIES; i++) acc += Nearest3Test.reference(packed, qx[i], qy[i], qz[i], out)[3];
            long t1 = System.nanoTime();
            for (int i = 0; i < QUERIES; i++) acc += Nearest3.search(xs, ys, zs, 12, qx[i], qy[i], qz[i]);
            long t2 = System.nanoTime();
            sink = acc;
            if (t >= 0) {
                naive[t] = (t1 - t0) / (double) QUERIES;
                kernel[t] = (t2 - t1) / (double) QUERIES;
            }
        }
        Arrays.sort(naive);
        Arrays.sort(kernel);
        System.out.printf("Nearest3 (12 candidates), median ns/query: packed-long loop %.1f, int[] kernel %.1f (x%.2f)%n",
                naive[MEASURED_TRIALS / 2], kernel[MEASURED_TRIALS / 2], naive[MEASURED_TRIALS / 2] / kernel[MEASURED_TRIALS / 2]);
    }
}
