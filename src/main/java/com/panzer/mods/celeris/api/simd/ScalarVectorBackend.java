package com.panzer.mods.celeris.api.simd;

/**
 * Pure-Java scalar fallback, used when {@code jdk.incubator.vector} isn't
 * usable on this JVM (missing {@code --add-modules=jdk.incubator.vector}).
 * Same semantics as {@code SimdVectorBackend} for every method, just without
 * the lane-width parallelism -- correctness-equivalent, throughput-inferior,
 * exactly the same tradeoff {@code HeapMemoryBackend} makes for memory.
 *
 * <p>Only the original five abstract operations are implemented here; every
 * later operation on {@link VectorBackend} is declared with a scalar
 * {@code default} body, which <em>is</em> this backend's implementation.
 * Keep it that way: one scalar reference per operation, in one place.
 */
final class ScalarVectorBackend implements VectorBackend {

    // No real hardware lane width applies to a scalar loop; 1 keeps this
    // an honest answer for any caller that sizes work batches off of it
    // (a batch size of "1 lane" degrades to per-element processing, which
    // is exactly what this backend does anyway).
    private static final int SCALAR_LANE_COUNT = 1;

    @Override
    public int preferredLaneCount() {
        return SCALAR_LANE_COUNT;
    }

    @Override
    public void addInPlace(float[] a, float[] b, int length) {
        for (int i = 0; i < length; i++) {
            a[i] += b[i];
        }
    }

    @Override
    public void subtractInPlace(float[] a, float[] b, int length) {
        for (int i = 0; i < length; i++) {
            a[i] -= b[i];
        }
    }

    @Override
    public float dotProduct(float[] a, float[] b, int length) {
        float sum = 0f;
        for (int i = 0; i < length; i++) {
            sum = Math.fma(a[i], b[i], sum);
        }
        return sum;
    }

    @Override
    public void thresholdMask(float[] values, boolean[] outMask, int length, float threshold) {
        for (int i = 0; i < length; i++) {
            outMask[i] = values[i] > threshold;
        }
    }

    @Override
    public void greaterThanMask(float[] a, float[] b, boolean[] outMask, int length) {
        for (int i = 0; i < length; i++) {
            outMask[i] = a[i] > b[i];
        }
    }

    @Override
    public String name() {
        return "scalar (compat mode)";
    }
}
