package com.panzer.mods.celeris.physics;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import static com.panzer.mods.celeris.physics.BodyLayout.*;

/**
 * Vector API streaming passes for the Java kernel: whole vectors only (the
 * caller rounds {@code end} up into the slab's padding lanes), loads and
 * stores straight on the off-heap slab. {@code SPECIES_PREFERRED} is 4 lanes
 * on AVX2, 8 on AVX-512, 2 on NEON; {@link BodyLayout#LANE_PAD} (16) is a
 * multiple of all of them.
 *
 * <p>Vanilla mode uses separate {@code sub}/{@code mul} lanewise ops, which
 * C2 compiles to the same IEEE instructions as the scalar code, so results
 * are bit-identical to {@link ScalarPhysicsKernel}. Fused mode uses
 * {@code FMA}, matching {@code Math.fma}.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SimdStreamKernel implements StreamKernel {

    private static final VectorSpecies<Double> D = DoubleVector.SPECIES_PREFERRED;
    private static final ByteOrder NATIVE = ByteOrder.nativeOrder();

    @Override
    public String name() {
        return "simd" + D.length() * 64;
    }

    @Override
    public void gravity(Object slabObject, int capacity, int begin, int end) {
        MemorySegment s = (MemorySegment) slabObject;
        long vy = f64Offset(VEL_Y, capacity);
        long g = f64Offset(GRAVITY, capacity);
        for (int i = begin; i < end; i += D.length()) {
            long o = (long) i << 3;
            DoubleVector.fromMemorySegment(D, s, vy + o, NATIVE)
                    .sub(DoubleVector.fromMemorySegment(D, s, g + o, NATIVE))
                    .intoMemorySegment(s, vy + o, NATIVE);
        }
    }

    @Override
    public void damp(Object slabObject, int capacity, int begin, int end, boolean fused) {
        MemorySegment s = (MemorySegment) slabObject;
        long vx = f64Offset(VEL_X, capacity), vy = f64Offset(VEL_Y, capacity), vz = f64Offset(VEL_Z, capacity);
        long fh = f64Offset(FACTOR_H, capacity), fv = f64Offset(FACTOR_V, capacity), g = f64Offset(GRAVITY, capacity);
        for (int i = begin; i < end; i += D.length()) {
            long o = (long) i << 3;
            DoubleVector h = DoubleVector.fromMemorySegment(D, s, fh + o, NATIVE);
            DoubleVector.fromMemorySegment(D, s, vx + o, NATIVE).mul(h).intoMemorySegment(s, vx + o, NATIVE);
            DoubleVector.fromMemorySegment(D, s, vz + o, NATIVE).mul(h).intoMemorySegment(s, vz + o, NATIVE);
            DoubleVector y = DoubleVector.fromMemorySegment(D, s, vy + o, NATIVE);
            DoubleVector v = DoubleVector.fromMemorySegment(D, s, fv + o, NATIVE);
            (fused
                    ? y.lanewise(VectorOperators.FMA, v, DoubleVector.fromMemorySegment(D, s, g + o, NATIVE).neg())
                    : y.mul(v))
                    .intoMemorySegment(s, vy + o, NATIVE);
        }
    }
}
