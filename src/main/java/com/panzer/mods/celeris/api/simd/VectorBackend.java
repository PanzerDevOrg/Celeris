package com.panzer.mods.celeris.api.simd;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Abstracts elementwise float-array math so {@link VectorOperations}'s
 * static facade can run on either a real SIMD implementation ({@code
 * SimdVectorBackend}, living in the {@code vector} JVM module, backed by
 * {@code jdk.incubator.vector}) or a plain scalar loop ({@link
 * ScalarVectorBackend}, in {@code main}) depending on what {@link
 * CelerisVectorRuntime} determines is available on this JVM --
 * {@code jdk.incubator.vector} is an incubator module on every JDK version
 * this mod targets and requires {@code --add-modules=jdk.incubator.vector},
 * so a JVM launched without that flag needs a working fallback, exactly
 * like the FFM-vs-heap memory split.
 *
 * <p>All methods operate on plain {@code float[]}/{@code boolean[]} -- no
 * vector-API type appears in this contract, so callers of {@link
 * VectorOperations} never need to know or care which implementation is
 * active.
 */
public interface VectorBackend {

    int preferredLaneCount();

    void addInPlace(float[] a, float[] b, int length);

    void subtractInPlace(float[] a, float[] b, int length);

    float dotProduct(float[] a, float[] b, int length);

    void thresholdMask(float[] values, boolean[] outMask, int length, float threshold);

    void greaterThanMask(float[] a, float[] b, boolean[] outMask, int length);

    //* Every method below has a correct scalar default, so
    //* pre-existing third-party backends keep compiling; both shipped
    //* backends override them. Defaults are also the pure-Java fallback
    //* contract: SIMD implementations must be bit-for-bit equivalent except
    //* where floating-point reassociation is documented (sum/dot).

    /** {@code a[i] += scalar}. */
    default void addScalarInPlace(float[] a, float scalar, int length) {
        for (int i = 0; i < length; i++) {
            a[i] += scalar;
        }
    }

    /** {@code a[i] *= scalar}. */
    default void multiplyScalarInPlace(float[] a, float scalar, int length) {
        for (int i = 0; i < length; i++) {
            a[i] *= scalar;
        }
    }

    /** {@code y[i] = alpha * x[i] + y[i]} (BLAS axpy). */
    default void axpyInPlace(float[] y, float[] x, float alpha, int length) {
        for (int i = 0; i < length; i++) {
            y[i] = Math.fma(alpha, x[i], y[i]);
        }
    }

    /** {@code a[i] = min(max(a[i], lo), hi)}. NaN lanes propagate as NaN. */
    @SuppressWarnings("MathClampMigration")
    default void clampInPlace(float[] a, float lo, float hi, int length) {
        for (int i = 0; i < length; i++) {
            a[i] = Math.min(Math.max(a[i], lo), hi);
        }
    }

    /** Sum of the first {@code length} lanes. Reassociation permitted. */
    default float sum(float[] a, int length) {
        float s = 0f;
        for (int i = 0; i < length; i++) {
            s += a[i];
        }
        return s;
    }

    /** Max of the first {@code length} lanes; {@code -Infinity} for length 0. */
    default float max(float[] a, int length) {
        float m = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < length; i++) {
            m = Math.max(m, a[i]);
        }
        return m;
    }

    /**
     * Bit-packed variant of {@link #thresholdMask}: bit {@code i} of
     * {@code outBits[i >>> 6]} is set iff {@code values[i] > threshold}.
     * Lanes {@code >= length} in the last touched word are cleared;
     * {@code outBits} must hold at least {@code (length + 63) >>> 6} words.
     */
    default void thresholdMaskBits(float[] values, long[] outBits, int length, float threshold) {
        int words = (length + 63) >>> 6;
        for (int w = 0; w < words; w++) {
            long bits = 0L;
            int base = w << 6;
            int end = Math.min(64, length - base);
            for (int j = 0; j < end; j++) {
                if (values[base + j] > threshold) {
                    bits |= 1L << j;
                }
            }
            outBits[w] = bits;
        }
    }

    /** Number of lanes with {@code values[i] > threshold}. */
    default int countGreaterThan(float[] values, float threshold, int length) {
        int n = 0;
        for (int i = 0; i < length; i++) {
            if (values[i] > threshold) {
                n++;
            }
        }
        return n;
    }

    //* Off-heap lanes. Operate directly on a MemoryBackend allocation so the
    //* discrete solver's 1-byte-per-node signal store and any float SoA held
    //* off-heap never round-trip through a heap array. Defaults go through
    //* the backend's primitive accessors; the SIMD backend uses
    //* MemorySegment lanes when the active memory backend is FFM.

    /**
     * Unsigned saturating subtract on bytes: {@code b[i] = max(0, b[i] - amount)}
     * treating each byte as 0..255.
     */
    @SuppressWarnings("ManualMinMaxCalculation")
    default void saturatingSubtractBytes(MemoryBackend backend, long handle, long offset, int length, int amount) {
        for (int i = 0; i < length; i++) {
            int v = (backend.getByte(handle, offset + i) & 0xFF) - amount;
            backend.setByte(handle, offset + i, (byte) (v < 0 ? 0 : v));
        }
    }

    /** Max of {@code length} unsigned bytes; 0 for length 0. */
    default int maxUnsignedByte(MemoryBackend backend, long handle, long offset, int length) {
        int m = 0;
        for (int i = 0; i < length; i++) {
            int v = backend.getByte(handle, offset + i) & 0xFF;
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    /** Count of unsigned bytes {@code > threshold}. */
    default int countBytesGreaterThan(MemoryBackend backend, long handle, long offset, int length, int threshold) {
        int n = 0;
        for (int i = 0; i < length; i++) {
            if ((backend.getByte(handle, offset + i) & 0xFF) > threshold) {
                n++;
            }
        }
        return n;
    }

    /** Copies {@code length} little-endian floats from off-heap into {@code dst}. */
    default void loadFloats(MemoryBackend backend, long handle, long offset, float[] dst, int dstIndex, int length) {
        for (int i = 0; i < length; i++) {
            dst[dstIndex + i] = backend.getFloat(handle, offset + ((long) i << 2));
        }
    }

    /** Copies {@code length} floats from {@code src} into off-heap little-endian storage. */
    default void storeFloats(MemoryBackend backend, long handle, long offset, float[] src, int srcIndex, int length) {
        for (int i = 0; i < length; i++) {
            backend.setFloat(handle, offset + ((long) i << 2), src[srcIndex + i]);
        }
    }

    /** Off-heap {@code dst[i] += src[i]} on float lanes stored in {@code backend}. */
    default void addFloatsOffHeap(MemoryBackend backend, long dstHandle, long dstOffset,
                                  long srcHandle, long srcOffset, int length) {
        for (int i = 0; i < length; i++) {
            long o = (long) i << 2;
            backend.setFloat(dstHandle, dstOffset + o,
                    backend.getFloat(dstHandle, dstOffset + o) + backend.getFloat(srcHandle, srcOffset + o));
        }
    }

    /** Short name for logging/diagnostics (e.g. "SIMD", "scalar (compat mode)"). */
    String name();
}
