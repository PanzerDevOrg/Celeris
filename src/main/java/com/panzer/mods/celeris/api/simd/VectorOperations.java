package com.panzer.mods.celeris.api.simd;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Elementwise float-array math, delegating to whichever {@link
 * VectorBackend} {@link CelerisVectorRuntime} selected for this JVM (real
 * SIMD via {@code jdk.incubator.vector}, or a pure-Java scalar fallback).
 *
 * <p>You don't choose or check the backend to use this class -- just call
 * the methods below, they always give a correct result. Use
 * {@link #isSimdActive()} only if you want to log/report which path is
 * active, e.g. for a debug screen.
 *
 * <pre>{@code
 * VectorOperations.addInPlace(a, b, a.length);
 * float dot = VectorOperations.dotProduct(a, b, a.length);
 * }</pre>
 *
 * <p>Calls dispatch to the SIMD backend when {@code jdk.incubator.vector} is
 * available and to a scalar implementation otherwise; results are the same
 * either way, so callers never need to check which one is active.
 *
 * @since 0.1.0
 */
public final class VectorOperations {

    private VectorOperations() {
    }

    public static int preferredLaneCount() {
        return CelerisVectorRuntime.backend().preferredLaneCount();
    }

    public static void addInPlace(float[] a, float[] b, int length) {
        CelerisVectorRuntime.backend().addInPlace(a, b, length);
    }

    /** {@code a[i] -= b[i]} for every lane, in place on {@code a}. */
    public static void subtractInPlace(float[] a, float[] b, int length) {
        CelerisVectorRuntime.backend().subtractInPlace(a, b, length);
    }

    public static float dotProduct(float[] a, float[] b, int length) {
        return CelerisVectorRuntime.backend().dotProduct(a, b, length);
    }

    public static void thresholdMask(float[] values, boolean[] outMask, int length, float threshold) {
        CelerisVectorRuntime.backend().thresholdMask(values, outMask, length, threshold);
    }

    /** {@code outMask[i] = a[i] > b[i]} for every lane -- elementwise, unlike {@link #thresholdMask}. */
    public static void greaterThanMask(float[] a, float[] b, boolean[] outMask, int length) {
        CelerisVectorRuntime.backend().greaterThanMask(a, b, outMask, length);
    }

    /** {@code a[i] += scalar}. */
    public static void addScalarInPlace(float[] a, float scalar, int length) {
        CelerisVectorRuntime.backend().addScalarInPlace(a, scalar, length);
    }

    /** {@code a[i] *= scalar}. */
    public static void multiplyScalarInPlace(float[] a, float scalar, int length) {
        CelerisVectorRuntime.backend().multiplyScalarInPlace(a, scalar, length);
    }

    /** {@code y[i] = alpha * x[i] + y[i]}. */
    public static void axpyInPlace(float[] y, float[] x, float alpha, int length) {
        CelerisVectorRuntime.backend().axpyInPlace(y, x, alpha, length);
    }

    /** {@code a[i] = clamp(a[i], lo, hi)}. */
    public static void clampInPlace(float[] a, float lo, float hi, int length) {
        CelerisVectorRuntime.backend().clampInPlace(a, lo, hi, length);
    }

    public static float sum(float[] a, int length) {
        return CelerisVectorRuntime.backend().sum(a, length);
    }

    public static float max(float[] a, int length) {
        return CelerisVectorRuntime.backend().max(a, length);
    }

    /** Bit-packed {@link #thresholdMask}; see {@link VectorBackend#thresholdMaskBits}. */
    public static void thresholdMaskBits(float[] values, long[] outBits, int length, float threshold) {
        CelerisVectorRuntime.backend().thresholdMaskBits(values, outBits, length, threshold);
    }

    public static int countGreaterThan(float[] values, float threshold, int length) {
        return CelerisVectorRuntime.backend().countGreaterThan(values, threshold, length);
    }

    /** Off-heap unsigned saturating {@code b[i] = max(0, b[i] - amount)}. */
    public static void saturatingSubtractBytes(MemoryBackend backend, long handle, long offset, int length, int amount) {
        CelerisVectorRuntime.backend().saturatingSubtractBytes(backend, handle, offset, length, amount);
    }

    public static int maxUnsignedByte(MemoryBackend backend, long handle, long offset, int length) {
        return CelerisVectorRuntime.backend().maxUnsignedByte(backend, handle, offset, length);
    }

    public static int countBytesGreaterThan(MemoryBackend backend, long handle, long offset, int length, int threshold) {
        return CelerisVectorRuntime.backend().countBytesGreaterThan(backend, handle, offset, length, threshold);
    }

    public static void loadFloats(MemoryBackend backend, long handle, long offset, float[] dst, int dstIndex, int length) {
        CelerisVectorRuntime.backend().loadFloats(backend, handle, offset, dst, dstIndex, length);
    }

    public static void storeFloats(MemoryBackend backend, long handle, long offset, float[] src, int srcIndex, int length) {
        CelerisVectorRuntime.backend().storeFloats(backend, handle, offset, src, srcIndex, length);
    }

    public static void addFloatsOffHeap(MemoryBackend backend, long dstHandle, long dstOffset,
                                        long srcHandle, long srcOffset, int length) {
        CelerisVectorRuntime.backend().addFloatsOffHeap(backend, dstHandle, dstOffset, srcHandle, srcOffset, length);
    }

    /**
     * Whether the active backend is real SIMD, as opposed to the scalar
     * fallback. Triggers backend selection on first call.
     *
     * <p>This is the method backing the public
     * {@code CelerisFeatures.isSimdActive()} -- prefer that entry point
     * from outside this package.
     */
    public static boolean isSimdActive() {
        return CelerisVectorRuntime.isSimdActive();
    }

    /** Short backend name for logs/diagnostics, e.g. {@code "SIMD (256-bit)"} or {@code "scalar (compat mode)"}. */
    public static String backendName() {
        return CelerisVectorRuntime.backend().name();
    }
}
