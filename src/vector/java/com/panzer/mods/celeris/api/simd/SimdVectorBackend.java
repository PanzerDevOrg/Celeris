package com.panzer.mods.celeris.api.simd;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Real SIMD implementation via {@code jdk.incubator.vector}. Lives in the
 * {@code vector} JVM module (see {@code [jvm.modules.vector]} in
 * stonecutter.properties.toml, {@code src/vector/java}) -- exactly like
 * {@code FfmMemoryBackend} lives in {@code ffm}, and for the same reason:
 * loading this class on a JVM launched without
 * {@code --add-modules=jdk.incubator.vector} throws at class-init time
 * (the {@code SPECIES} field lookup fails), and {@code main} must be
 * compilable and runnable with zero knowledge of {@code jdk.incubator.vector}
 * ever existing.
 *
 * <p>Public and named by fully-qualified string in {@code
 * CelerisVectorRuntime}, which loads it reflectively via {@code
 * Class.forName} -- {@code main} never references this class (or {@code
 * jdk.incubator.vector}) at the bytecode level, so {@code compileJava}
 * needs neither {@code --enable-preview} nor {@code --add-modules} for
 * vector support.
 */
public final class SimdVectorBackend implements VectorBackend {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public int preferredLaneCount() {
        return SPECIES.length();
    }

    @Override
    public void addInPlace(float[] a, float[] b, int length) {
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            va.add(vb).intoArray(a, i);
        }
        for (; i < length; i++) {
            a[i] += b[i];
        }
    }

    @Override
    public void subtractInPlace(float[] a, float[] b, int length) {
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            va.sub(vb).intoArray(a, i);
        }
        for (; i < length; i++) {
            a[i] -= b[i];
        }
    }

    @Override
    public float dotProduct(float[] a, float[] b, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector accumulator = FloatVector.zero(SPECIES);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            accumulator = va.fma(vb, accumulator);
        }
        float sum = accumulator.reduceLanes(VectorOperators.ADD);
        for (; i < length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Override
    public void thresholdMask(float[] values, boolean[] outMask, int length, float threshold) {
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector v = FloatVector.fromArray(SPECIES, values, i);
            var mask = v.compare(VectorOperators.GT, threshold);
            mask.intoArray(outMask, i);
        }
        for (; i < length; i++) {
            outMask[i] = values[i] > threshold;
        }
    }

    @Override
    public void greaterThanMask(float[] a, float[] b, boolean[] outMask, int length) {
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            var mask = va.compare(VectorOperators.GT, vb);
            mask.intoArray(outMask, i);
        }
        for (; i < length; i++) {
            outMask[i] = a[i] > b[i];
        }
    }

    // ---------------------------------------------------------------
    // Additive heap operations
    // ---------------------------------------------------------------

    @Override
    public void addScalarInPlace(float[] a, float scalar, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector vs = FloatVector.broadcast(SPECIES, scalar);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector.fromArray(SPECIES, a, i).add(vs).intoArray(a, i);
        }
        for (; i < length; i++) {
            a[i] += scalar;
        }
    }

    @Override
    public void multiplyScalarInPlace(float[] a, float scalar, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector vs = FloatVector.broadcast(SPECIES, scalar);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector.fromArray(SPECIES, a, i).mul(vs).intoArray(a, i);
        }
        for (; i < length; i++) {
            a[i] *= scalar;
        }
    }

    @Override
    public void axpyInPlace(float[] y, float[] x, float alpha, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector va = FloatVector.broadcast(SPECIES, alpha);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector vx = FloatVector.fromArray(SPECIES, x, i);
            FloatVector vy = FloatVector.fromArray(SPECIES, y, i);
            vx.fma(va, vy).intoArray(y, i);
        }
        for (; i < length; i++) {
            y[i] = Math.fma(alpha, x[i], y[i]);
        }
    }

    @Override
    public void clampInPlace(float[] a, float lo, float hi, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector vlo = FloatVector.broadcast(SPECIES, lo);
        FloatVector vhi = FloatVector.broadcast(SPECIES, hi);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            // Vector max/min match Math.max/min NaN semantics (NaN propagates).
            FloatVector.fromArray(SPECIES, a, i).max(vlo).min(vhi).intoArray(a, i);
        }
        for (; i < length; i++) {
            a[i] = Math.min(Math.max(a[i], lo), hi);
        }
    }

    @Override
    public float sum(float[] a, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector acc = FloatVector.zero(SPECIES);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            acc = acc.add(FloatVector.fromArray(SPECIES, a, i));
        }
        float s = acc.reduceLanes(VectorOperators.ADD);
        for (; i < length; i++) {
            s += a[i];
        }
        return s;
    }

    @Override
    public float max(float[] a, int length) {
        int upperBound = SPECIES.loopBound(length);
        float m = Float.NEGATIVE_INFINITY;
        int i = 0;
        if (upperBound > 0) {
            FloatVector acc = FloatVector.fromArray(SPECIES, a, 0);
            for (i = SPECIES.length(); i < upperBound; i += SPECIES.length()) {
                acc = acc.max(FloatVector.fromArray(SPECIES, a, i));
            }
            m = acc.reduceLanes(VectorOperators.MAX);
        }
        for (; i < length; i++) {
            m = Math.max(m, a[i]);
        }
        return m;
    }

    @Override
    public void thresholdMaskBits(float[] values, long[] outBits, int length, float threshold) {
        // Species length is a power of two <= 64 for every float species, so a
        // 64-bit word always holds a whole number of lane-masks: pack them by
        // shifting each mask's toLong() into position. Tail lanes handled by
        // a masked load so out-of-range lanes read as 0 and compare false
        // (threshold is finite in all practical uses; -Infinity threshold
        // would need the scalar path, which the tail loop below covers).
        int lanes = SPECIES.length();
        FloatVector vt = FloatVector.broadcast(SPECIES, threshold);
        int words = (length + 63) >>> 6;
        int i = 0;
        for (int w = 0; w < words; w++) {
            long bits = 0L;
            int wordEnd = Math.min(length, (w + 1) << 6);
            int shift = 0;
            for (; i + lanes <= wordEnd; i += lanes, shift += lanes) {
                bits |= FloatVector.fromArray(SPECIES, values, i).compare(VectorOperators.GT, vt).toLong() << shift;
            }
            for (; i < wordEnd; i++, shift++) {
                if (values[i] > threshold) {
                    bits |= 1L << shift;
                }
            }
            outBits[w] = bits;
        }
    }

    @Override
    public int countGreaterThan(float[] values, float threshold, int length) {
        int upperBound = SPECIES.loopBound(length);
        FloatVector vt = FloatVector.broadcast(SPECIES, threshold);
        int n = 0;
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            n += FloatVector.fromArray(SPECIES, values, i).compare(VectorOperators.GT, vt).trueCount();
        }
        for (; i < length; i++) {
            if (values[i] > threshold) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------
    // Off-heap operations: MemorySegment lanes via SimdSegmentLanes when the
    // memory backend exposes a native segment AND that (preview-compiled)
    // bridge class links on this JVM; otherwise the scalar interface defaults.
    // ---------------------------------------------------------------

    private static final int LANES_UNKNOWN = 0, LANES_OK = 1, LANES_UNAVAILABLE = -1;
    private static volatile int segmentLanesState = LANES_UNKNOWN;

    private static boolean segmentLanesAvailable() {
        int s = segmentLanesState;
        if (s == LANES_UNKNOWN) {
            try {
                SimdSegmentLanes.probe();
                s = LANES_OK;
            } catch (Throwable t) {
                s = LANES_UNAVAILABLE;
            }
            segmentLanesState = s;
        }
        return s == LANES_OK;
    }

    /** Native segment for {@code handle}, or null if the scalar path must be used. */
    private static Object segmentOrNull(MemoryBackend backend, long handle) {
        Object seg = backend.nativeSegment(handle);
        return seg != null && segmentLanesAvailable() ? seg : null;
    }

    @Override
    public void saturatingSubtractBytes(MemoryBackend backend, long handle, long offset, int length, int amount) {
        Object seg = segmentOrNull(backend, handle);
        if (seg == null) {
            VectorBackend.super.saturatingSubtractBytes(backend, handle, offset, length, amount);
            return;
        }
        SimdSegmentLanes.saturatingSubtractBytes(seg, offset, length, amount);
    }

    @Override
    public int maxUnsignedByte(MemoryBackend backend, long handle, long offset, int length) {
        Object seg = segmentOrNull(backend, handle);
        return seg == null
                ? VectorBackend.super.maxUnsignedByte(backend, handle, offset, length)
                : SimdSegmentLanes.maxUnsignedByte(seg, offset, length);
    }

    @Override
    public int countBytesGreaterThan(MemoryBackend backend, long handle, long offset, int length, int threshold) {
        Object seg = segmentOrNull(backend, handle);
        return seg == null
                ? VectorBackend.super.countBytesGreaterThan(backend, handle, offset, length, threshold)
                : SimdSegmentLanes.countBytesGreaterThan(seg, offset, length, threshold);
    }

    @Override
    public void loadFloats(MemoryBackend backend, long handle, long offset, float[] dst, int dstIndex, int length) {
        Object seg = segmentOrNull(backend, handle);
        if (seg == null) {
            VectorBackend.super.loadFloats(backend, handle, offset, dst, dstIndex, length);
            return;
        }
        SimdSegmentLanes.loadFloats(seg, offset, dst, dstIndex, length);
    }

    @Override
    public void storeFloats(MemoryBackend backend, long handle, long offset, float[] src, int srcIndex, int length) {
        Object seg = segmentOrNull(backend, handle);
        if (seg == null) {
            VectorBackend.super.storeFloats(backend, handle, offset, src, srcIndex, length);
            return;
        }
        SimdSegmentLanes.storeFloats(seg, offset, src, srcIndex, length);
    }

    @Override
    public void addFloatsOffHeap(MemoryBackend backend, long dstHandle, long dstOffset,
                                 long srcHandle, long srcOffset, int length) {
        Object dst = segmentOrNull(backend, dstHandle);
        Object src = dst == null ? null : backend.nativeSegment(srcHandle);
        if (dst == null || src == null) {
            VectorBackend.super.addFloatsOffHeap(backend, dstHandle, dstOffset, srcHandle, srcOffset, length);
            return;
        }
        SimdSegmentLanes.addFloats(dst, dstOffset, src, srcOffset, length);
    }

    @Override
    public String name() {
        return "SIMD (" + SPECIES.length() + "-wide)";
    }
}
