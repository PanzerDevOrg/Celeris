package com.panzer.mods.celeris.util.math;

/**
 * Packed 64-bit block-state descriptor.
 *
 * <p>Bit layout (LSB first):
 * <pre>
 *   [ 0..31] state id      (32 bits, unsigned)
 *   [   32 ] solid
 *   [   33 ] opaque
 *   [   34 ] ticks
 *   [   35 ] has capability
 *   [   36 ] emits light
 *   [37..63] reserved
 * </pre>
 * All accessors are pure bit ops (mask/shift), no branching on the packed value itself.
 */
public final class StateBitmask {

    private static final int STATE_ID_BITS = 32;
    private static final long STATE_ID_MASK = (1L << STATE_ID_BITS) - 1L;

    private static final int SOLID_SHIFT = 32;
    private static final int OPAQUE_SHIFT = 33;
    private static final int TICKS_SHIFT = 34;
    private static final int HAS_CAPABILITY_SHIFT = 35;
    private static final int EMITS_LIGHT_SHIFT = 36;

    private static final long FLAG_SOLID = 1L << SOLID_SHIFT;
    private static final long FLAG_OPAQUE = 1L << OPAQUE_SHIFT;
    private static final long FLAG_TICKS = 1L << TICKS_SHIFT;
    private static final long FLAG_HAS_CAPABILITY = 1L << HAS_CAPABILITY_SHIFT;
    private static final long FLAG_EMITS_LIGHT = 1L << EMITS_LIGHT_SHIFT;

    /** Bits that participate in a "structural" change (id + capability presence). */
    private static final long STRUCTURAL_MASK = STATE_ID_MASK | FLAG_HAS_CAPABILITY;

    private StateBitmask() {
    }

    public static long pack(int blockStateId, boolean solid, boolean opaque, boolean ticks,
                             boolean hasCapability, boolean emitsLight) {
        return (blockStateId & STATE_ID_MASK)
                | (boolToBit(solid) << SOLID_SHIFT)
                | (boolToBit(opaque) << OPAQUE_SHIFT)
                | (boolToBit(ticks) << TICKS_SHIFT)
                | (boolToBit(hasCapability) << HAS_CAPABILITY_SHIFT)
                | (boolToBit(emitsLight) << EMITS_LIGHT_SHIFT);
    }

    public static int stateId(long packed) {
        return (int) (packed & STATE_ID_MASK);
    }

    public static boolean isSolid(long packed) {
        return (packed & FLAG_SOLID) != 0L;
    }

    public static boolean isOpaque(long packed) {
        return (packed & FLAG_OPAQUE) != 0L;
    }

    public static boolean ticks(long packed) {
        return (packed & FLAG_TICKS) != 0L;
    }

    public static boolean hasCapability(long packed) {
        return (packed & FLAG_HAS_CAPABILITY) != 0L;
    }

    public static boolean emitsLight(long packed) {
        return (packed & FLAG_EMITS_LIGHT) != 0L;
    }

    /** Sets/clears the state id bits only, preserving all flags. */
    public static long withStateId(long packed, int blockStateId) {
        return (packed & ~STATE_ID_MASK) | (blockStateId & STATE_ID_MASK);
    }

    public static long withSolid(long packed, boolean solid) {
        return setFlag(packed, FLAG_SOLID, solid);
    }

    public static long withOpaque(long packed, boolean opaque) {
        return setFlag(packed, FLAG_OPAQUE, opaque);
    }

    public static long withTicks(long packed, boolean ticks) {
        return setFlag(packed, FLAG_TICKS, ticks);
    }

    public static long withCapability(long packed, boolean hasCapability) {
        return setFlag(packed, FLAG_HAS_CAPABILITY, hasCapability);
    }

    public static long withEmitsLight(long packed, boolean emitsLight) {
        return setFlag(packed, FLAG_EMITS_LIGHT, emitsLight);
    }

    /**
     * True if {@code previous} and {@code next} differ in state id or capability
     * presence (the fields that require capability invalidation). Single XOR + AND,
     * no per-field branching.
     */
    public static boolean isStructuralChange(long previous, long next) {
        return ((previous ^ next) & STRUCTURAL_MASK) != 0L;
    }

    private static long setFlag(long packed, long flagMask, boolean value) {
        return (packed & ~flagMask) | (boolToBit(value) << Long.numberOfTrailingZeros(flagMask));
    }

    private static long boolToBit(boolean value) {
        return value ? 1L : 0L;
    }
}
