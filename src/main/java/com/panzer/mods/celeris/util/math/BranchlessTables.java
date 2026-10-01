package com.panzer.mods.celeris.util.math;

/**
 * Direction lookup tables and branch-free scalar primitives shared across the engine.
 * Every method here is a straight-line sequence of bit ops (shift/and/xor) with no
 * conditional jumps on the hot path, intended for use in per-block / per-tick loops
 * where predictable branchless code out-scales a branchy equivalent.
 *
 * <p>Direction indices follow vanilla's {@code Direction} ordinal order:
 * {@code 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST}.
 */
public final class BranchlessTables {

    private static final int[] OPPOSITE_DIRECTION = {1, 0, 3, 2, 5, 4};
    private static final int[] AXIS_OF_DIRECTION = {1, 1, 2, 2, 0, 0};
    private static final int[] DIRECTION_STEP_X = {0, 0, 0, 0, -1, 1};
    private static final int[] DIRECTION_STEP_Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] DIRECTION_STEP_Z = {0, 0, -1, 1, 0, 0};

    private BranchlessTables() {
    }

    public static int opposite(int directionIndex) {
        return OPPOSITE_DIRECTION[directionIndex];
    }

    public static int axisOf(int directionIndex) {
        return AXIS_OF_DIRECTION[directionIndex];
    }

    public static int stepX(int directionIndex) {
        return DIRECTION_STEP_X[directionIndex];
    }

    public static int stepY(int directionIndex) {
        return DIRECTION_STEP_Y[directionIndex];
    }

    public static int stepZ(int directionIndex) {
        return DIRECTION_STEP_Z[directionIndex];
    }

    /** Branchless {@code condition ? ifTrue : ifFalse} for longs, via mask select. */
    public static long selectLong(boolean condition, long ifTrue, long ifFalse) {
        long mask = -(condition ? 1L : 0L);
        return (ifTrue & mask) | (ifFalse & ~mask);
    }

    /** Branchless {@code condition ? ifTrue : ifFalse} for ints, via mask select. */
    public static int selectInt(boolean condition, int ifTrue, int ifFalse) {
        int mask = -(condition ? 1 : 0);
        return (ifTrue & mask) | (ifFalse & ~mask);
    }

    /** Branchless sign: -1, 0 or 1, without comparisons. */
    public static int sign(int value) {
        return (value >> 31) | (-value >>> 31);
    }

    /** Branchless absolute value (two's-complement trick, no comparison). */
    public static int abs(int value) {
        int mask = value >> 31;
        return (value ^ mask) - mask;
    }

    /** Branchless min via XOR-mask select on the sign of the difference. */
    public static int min(int a, int b) {
        int diffSignMask = (a - b) >> 31;
        return (a & diffSignMask) | (b & ~diffSignMask);
    }

    /** Branchless max via XOR-mask select on the sign of the difference. */
    public static int max(int a, int b) {
        int diffSignMask = (a - b) >> 31;
        return (b & diffSignMask) | (a & ~diffSignMask);
    }

    /** Branchless clamp of {@code value} into {@code [low, high]}. */
    public static int clamp(int value, int low, int high) {
        return min(max(value, low), high);
    }

    /** True if {@code value} is a power of two (value &gt; 0 implied by result). */
    public static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0 && value != 0;
    }

    /** Rounds {@code value} up to the next multiple of a power-of-two {@code alignment}. */
    public static int alignUp(int value, int alignment) {
        int mask = alignment - 1;
        return (value + mask) & ~mask;
    }

    /** Rounds {@code value} up to the next multiple of a power-of-two {@code alignment} (64-bit). */
    public static long alignUp(long value, long alignment) {
        long mask = alignment - 1L;
        return (value + mask) & ~mask;
    }
}
