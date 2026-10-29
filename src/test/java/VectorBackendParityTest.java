import net.bichal.mods.celeris.api.simd.VectorOperations;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the SIMD and scalar backends promise to agree on: element-wise operations are
 * bit-identical; reductions (dot product, sum) are close but not bit-identical, because
 * the SIMD backend sums per lane. Skipped when the vector module is absent (then both
 * sides would be the scalar backend, which proves nothing).
 */
class VectorBackendParityTest {

    private static float[] randoms(int n, long seed) {
        Random r = new Random(seed);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (r.nextFloat() - 0.5f) * 8f;
        }
        return out;
    }

    @Test
    void elementWiseOperationsAreBitIdentical() {
        assumeTrue(net.bichal.mods.celeris.api.CelerisFeatures.isSimdActive(), "jdk.incubator.vector not present");
        for (int n : new int[] {1, 3, 7, 8, 16, 33, 64, 1000}) {
            float[] a = randoms(n, n);
            float[] b = randoms(n, n + 1);
            float[] viaApi = a.clone();
            float[] ref = a.clone();
            VectorOperations.addInPlace(viaApi, b, n);
            for (int i = 0; i < n; i++) ref[i] += b[i];
            for (int i = 0; i < n; i++) assertEquals(Float.floatToRawIntBits(ref[i]), Float.floatToRawIntBits(viaApi[i]), "add " + n + "@" + i);

            viaApi = a.clone();
            ref = a.clone();
            VectorOperations.axpyInPlace(viaApi, b, 0.37f, n);
            for (int i = 0; i < n; i++) ref[i] = Math.fma(0.37f, b[i], ref[i]);
            for (int i = 0; i < n; i++) assertEquals(Float.floatToRawIntBits(ref[i]), Float.floatToRawIntBits(viaApi[i]), "axpy " + n + "@" + i);
        }
    }

    @Test
    void reductionsAreCloseToTheSequentialResult() {
        assumeTrue(net.bichal.mods.celeris.api.CelerisFeatures.isSimdActive(), "jdk.incubator.vector not present");
        for (int n : new int[] {1, 3, 7, 8, 16, 33, 64, 1000}) {
            float[] a = randoms(n, 100 + n);
            float[] b = randoms(n, 200 + n);
            double exactDot = 0;
            double exactSum = 0;
            double magnitude = 0;
            for (int i = 0; i < n; i++) {
                exactDot += (double) a[i] * b[i];
                exactSum += a[i];
                magnitude += Math.abs((double) a[i] * b[i]) + Math.abs(a[i]);
            }
            double tolerance = magnitude * n * 1.2e-7;
            assertTrue(Math.abs(VectorOperations.dotProduct(a, b, n) - exactDot) <= tolerance, "dot " + n);
            assertTrue(Math.abs(VectorOperations.sum(a, n) - exactSum) <= tolerance, "sum " + n);
        }
    }
}
