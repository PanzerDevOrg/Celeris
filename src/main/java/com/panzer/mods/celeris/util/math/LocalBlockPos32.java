package com.panzer.mods.celeris.util.math;

/**
 * Packs a local-space block position (each axis in {@code [0, 15]}, i.e. a 16^3
 * section-local coordinate) into a single {@code int}.
 *
 * <p>Bit layout (LSB first): {@code [x:4][y:4][z:4]}, 12 bits used, 20 reserved.
 */
public final class LocalBlockPos32 {

    private static final int AXIS_BITS = 4;
    private static final int AXIS_MASK = (1 << AXIS_BITS) - 1;

    private static final int X_SHIFT = 0;
    private static final int Y_SHIFT = AXIS_BITS;
    private static final int Z_SHIFT = AXIS_BITS * 2;

    private LocalBlockPos32() {
    }

    public static int pack(int localX, int localY, int localZ) {
        return ((localX & AXIS_MASK) << X_SHIFT)
                | ((localY & AXIS_MASK) << Y_SHIFT)
                | ((localZ & AXIS_MASK) << Z_SHIFT);
    }

    public static int unpackX(int packed) {
        return (packed >> X_SHIFT) & AXIS_MASK;
    }

    public static int unpackY(int packed) {
        return (packed >> Y_SHIFT) & AXIS_MASK;
    }

    public static int unpackZ(int packed) {
        return (packed >> Z_SHIFT) & AXIS_MASK;
    }

    /** Replaces the X axis only, preserving Y/Z. */
    public static int withX(int packed, int localX) {
        return (packed & ~(AXIS_MASK << X_SHIFT)) | ((localX & AXIS_MASK) << X_SHIFT);
    }

    /** Replaces the Y axis only, preserving X/Z. */
    public static int withY(int packed, int localY) {
        return (packed & ~(AXIS_MASK << Y_SHIFT)) | ((localY & AXIS_MASK) << Y_SHIFT);
    }

    /** Replaces the Z axis only, preserving X/Y. */
    public static int withZ(int packed, int localZ) {
        return (packed & ~(AXIS_MASK << Z_SHIFT)) | ((localZ & AXIS_MASK) << Z_SHIFT);
    }

    /** Offsets a packed position by a direction step, wrapping within the 4-bit axis (mod 16). */
    public static int offset(int packed, int stepX, int stepY, int stepZ) {
        return pack(unpackX(packed) + stepX, unpackY(packed) + stepY, unpackZ(packed) + stepZ);
    }
}
