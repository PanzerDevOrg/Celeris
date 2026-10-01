package com.panzer.mods.celeris.api.simd;

import jdk.incubator.vector.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Vector lanes directly over {@link MemorySegment}s. The only class in the
 * {@code vector} module that touches {@code java.lang.foreign}, so it is
 * the only one javac marks as preview-dependent on JDK 21. {@code
 * SimdVectorBackend} links it lazily and falls back to scalar defaults if
 * this class fails to load (JVM without {@code --enable-preview}).
 *
 * <p>All entry points take the segment as {@code Object} (from {@code
 * MemoryBackend.nativeSegment}) and cast here.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SimdSegmentLanes {

    private static final VectorSpecies<Byte> B = ByteVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED;
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
    private static final byte SIGN_FLIP = (byte) 0x80;

    private SimdSegmentLanes() {
    }

    /** Forces class init + one segment lane op; throws if unusable. */
    static void probe() {
        byte[] tmp = new byte[B.length()];
        MemorySegment seg = MemorySegment.ofArray(tmp);
        ByteVector.fromMemorySegment(B, seg, 0, LE).intoMemorySegment(seg, 0, LE);
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    static void saturatingSubtractBytes(Object segment, long offset, int length, int amount) {
        MemorySegment seg = (MemorySegment) segment;
        if (amount <= 0) {
            return;
        }
        if (amount >= 255) {
            seg.asSlice(offset, length).fill((byte) 0);
            return;
        }
        byte amt = (byte) amount;
        ByteVector zero = ByteVector.zero(B);
        int upper = B.loopBound(length);
        int i = 0;
        for (; i < upper; i += B.length()) {
            ByteVector v = ByteVector.fromMemorySegment(B, seg, offset + i, LE);
            //? >=25 {
            //VectorMask<Byte> ge = v.compare(VectorOperators.UGE, amt);
            //?} else
            VectorMask<Byte> ge = v.compare(VectorOperators.UNSIGNED_GE, amt);
            zero.blend(v.sub(amt), ge).intoMemorySegment(seg, offset + i, LE);
        }
        for (; i < length; i++) {
            int v = (seg.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF) - amount;
            seg.set(ValueLayout.JAVA_BYTE, offset + i, (byte) (v < 0 ? 0 : v));
        }
    }

    static int maxUnsignedByte(Object segment, long offset, int length) {
        MemorySegment seg = (MemorySegment) segment;
        int upper = B.loopBound(length);
        int m = 0;
        int i = 0;
        if (upper > 0) {
            // XOR 0x80 maps unsigned order onto signed order so signed MAX works.
            ByteVector acc = ByteVector.fromMemorySegment(B, seg, offset, LE).lanewise(VectorOperators.XOR, SIGN_FLIP);
            for (i = B.length(); i < upper; i += B.length()) {
                acc = acc.max(ByteVector.fromMemorySegment(B, seg, offset + i, LE).lanewise(VectorOperators.XOR, SIGN_FLIP));
            }
            m = (acc.reduceLanes(VectorOperators.MAX) ^ SIGN_FLIP) & 0xFF;
        }
        for (; i < length; i++) {
            int v = seg.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF;
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    static int countBytesGreaterThan(Object segment, long offset, int length, int threshold) {
        if (threshold >= 255) {
            return 0;
        }
        if (threshold < 0) {
            return length;
        }
        MemorySegment seg = (MemorySegment) segment;
        byte t = (byte) threshold;
        int upper = B.loopBound(length);
        int n = 0;
        int i = 0;
        for (; i < upper; i += B.length()) {
            //? >=25 {
            //n += ByteVector.fromMemorySegment(B, seg, offset + i, LE).compare(VectorOperators.UGT, t).trueCount();
            //?} else
            n += ByteVector.fromMemorySegment(B, seg, offset + i, LE).compare(VectorOperators.UNSIGNED_GT, t).trueCount();
        }
        for (; i < length; i++) {
            if ((seg.get(ValueLayout.JAVA_BYTE, offset + i) & 0xFF) > threshold) {
                n++;
            }
        }
        return n;
    }

    static void loadFloats(Object segment, long offset, float[] dst, int dstIndex, int length) {
        MemorySegment seg = (MemorySegment) segment;
        int upper = F.loopBound(length);
        int i = 0;
        for (; i < upper; i += F.length()) {
            FloatVector.fromMemorySegment(F, seg, offset + ((long) i << 2), LE).intoArray(dst, dstIndex + i);
        }
        for (; i < length; i++) {
            dst[dstIndex + i] = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + ((long) i << 2));
        }
    }

    static void storeFloats(Object segment, long offset, float[] src, int srcIndex, int length) {
        MemorySegment seg = (MemorySegment) segment;
        int upper = F.loopBound(length);
        int i = 0;
        for (; i < upper; i += F.length()) {
            FloatVector.fromArray(F, src, srcIndex + i).intoMemorySegment(seg, offset + ((long) i << 2), LE);
        }
        for (; i < length; i++) {
            seg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset + ((long) i << 2), src[srcIndex + i]);
        }
    }

    static void addFloats(Object dstSegment, long dstOffset, Object srcSegment, long srcOffset, int length) {
        MemorySegment dst = (MemorySegment) dstSegment;
        MemorySegment src = (MemorySegment) srcSegment;
        int upper = F.loopBound(length);
        int i = 0;
        for (; i < upper; i += F.length()) {
            long o = (long) i << 2;
            FloatVector.fromMemorySegment(F, dst, dstOffset + o, LE)
                    .add(FloatVector.fromMemorySegment(F, src, srcOffset + o, LE))
                    .intoMemorySegment(dst, dstOffset + o, LE);
        }
        for (; i < length; i++) {
            long o = (long) i << 2;
            dst.set(ValueLayout.JAVA_FLOAT_UNALIGNED, dstOffset + o,
                    dst.get(ValueLayout.JAVA_FLOAT_UNALIGNED, dstOffset + o)
                            + src.get(ValueLayout.JAVA_FLOAT_UNALIGNED, srcOffset + o));
        }
    }
}
