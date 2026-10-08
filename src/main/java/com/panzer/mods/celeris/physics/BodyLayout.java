package com.panzer.mods.celeris.physics;

/**
 * Off-heap Structure-of-Arrays layout of a {@link BodyBatch}. Mirrors
 * {@code native/include/celeris_physics.h} exactly; {@code
 * NativePhysicsKernel} refuses to load a library whose
 * {@code cp_layout_signature()} differs from {@link #signature()}.
 *
 * <pre>
 * slab (64-byte aligned, capacity padded to a multiple of 16)
 * ├─ f64 column 0  POS_X          capacity * 8 bytes
 * ├─ f64 column 1  POS_Y
 * │  ...
 * ├─ f64 column 14 FACTOR_V
 * ├─ u32 column 0  FLAGS          capacity * 4 bytes
 * ├─ u32 column 1  DEFERRED_LIST
 * ├─ u32 column 2  CELL_HASH
 * └─ u32 column 3  SORTED
 * </pre>
 *
 * <p>With the capacity a multiple of {@link #LANE_PAD}, every f64 column is a
 * whole number of 128-byte blocks and every u32 column a whole number of
 * 64-byte cache lines. Work split at multiples of {@link #LANE_PAD} therefore
 * never puts two threads on the same line of any column (no false sharing),
 * and SIMD loops may run over the padding lanes instead of a scalar tail.
 */
public final class BodyLayout {

    public static final int ABI_VERSION = 2;
    public static final int LANE_PAD = 16;
    public static final long ALIGNMENT = 64;

    /** Largest parallel work unit: a multiple of {@link #LANE_PAD}, ~100 KB of hot columns (fits L2). */
    public static final int CHUNK = 1024;
    /** Smallest parallel work unit: below this a unit costs less than handing it to a worker. */
    public static final int MIN_UNIT = 256;
    /**
     * Bodies from which {@code step} uses the worker threads ({@code -Dceleris.physics.parallelThreshold}).
     * A step of 2048 bodies takes ~150-200 µs on one thread; waking the workers costs ~10-50 µs.
     */
    public static final int PARALLEL_THRESHOLD = Integer.getInteger("celeris.physics.parallelThreshold", 2048);
    /** Room for one deferred count per work unit, each on its own cache line (16 ints). */
    public static final int RESULT_STRIDE = 16;

    /**
     * Bodies per work unit for a step over {@code size} bodies with {@code threads}
     * workers plus the stepping thread: about four units per thread, so the
     * dynamic claiming can even out units whose collision cost differs, between
     * {@link #MIN_UNIT} and {@link #CHUNK}, a multiple of {@link #LANE_PAD} (the
     * native kernel's SIMD passes start and end on whole vectors).
     */
    public static int unit(int size, int threads) {
        if (threads <= 0 || size < PARALLEL_THRESHOLD) {
            return CHUNK;
        }
        int per = size / ((threads + 1) * 4);
        per = (per + LANE_PAD - 1) & -LANE_PAD;
        return Math.max(MIN_UNIT, Math.min(CHUNK, per));
    }

    /** Size of a per-unit deferred-count array for a batch of {@code capacity} bodies. */
    public static int resultSlots(int capacity) {
        return ((capacity + MIN_UNIT - 1) / MIN_UNIT) * RESULT_STRIDE;
    }

    // f64 columns
    public static final int POS_X = 0;
    public static final int POS_Y = 1;
    public static final int POS_Z = 2;
    public static final int VEL_X = 3;
    public static final int VEL_Y = 4;
    public static final int VEL_Z = 5;
    public static final int HALF_WIDTH = 6;
    public static final int HEIGHT = 7;
    public static final int GRAVITY = 8;
    public static final int DRAG_AIR_H = 9;
    public static final int DRAG_V = 10;
    /** Holds a float widened to double; ground multiplier = (double) (friction_f32 * scale_f32). */
    public static final int GROUND_SCALE = 11;
    /** Holds a float widened to double: the block below is at floor(y - offset). */
    public static final int BELOW_OFFSET = 12;
    public static final int FACTOR_H = 13;
    public static final int FACTOR_V = 14;
    public static final int F64_COLUMNS = 15;

    // u32 columns
    public static final int FLAGS = 0;
    public static final int DEFERRED_LIST = 1;
    public static final int CELL_HASH = 2;
    public static final int SORTED = 3;
    public static final int U32_COLUMNS = 4;

    private BodyLayout() {
    }

    /** Rounds a requested capacity up to a multiple of {@link #LANE_PAD}. */
    public static int roundCapacity(int requested) {
        if (requested <= 0 || requested > (Integer.MAX_VALUE - LANE_PAD)) {
            throw new IllegalArgumentException("capacity out of range: " + requested);
        }
        return (requested + LANE_PAD - 1) & -LANE_PAD;
    }

    public static long slabBytes(int capacity) {
        return (long) capacity * (8L * F64_COLUMNS + 4L * U32_COLUMNS);
    }

    public static long f64Offset(int column, int capacity) {
        return (long) column * capacity * 8L;
    }

    public static long u32Offset(int column, int capacity) {
        return (long) F64_COLUMNS * capacity * 8L + (long) column * capacity * 4L;
    }

    /** Same encoding as the native {@code cp_layout_signature()}. */
    public static int signature() {
        return (F64_COLUMNS << 16) | (U32_COLUMNS << 8) | LANE_PAD;
    }

    /** Hash buckets the broadphase needs for this capacity (power of two, &gt;= 2 * capacity). */
    public static int bucketCount(int capacity) {
        long want = (long) capacity * 2;
        long t = 16;
        while (t < want) {
            t <<= 1;
        }
        return (int) t;
    }
}
