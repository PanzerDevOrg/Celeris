package com.panzer.mods.celeris.physics;

/** Per-body flag bits of the {@link BodyLayout#FLAGS} column (mirrors {@code CP_FLAG_*}). */
public final class BodyFlags {

    /** In/out: vanilla {@code onGround}. */
    public static final int ON_GROUND = 1;
    /** Out: vanilla {@code horizontalCollision}. */
    public static final int H_COLLISION = 1 << 1;
    /** Out: vanilla {@code verticalCollision}. */
    public static final int V_COLLISION = 1 << 2;
    /** Out: the fast path could not model this body this tick; run the vanilla code instead. */
    public static final int DEFERRED = 1 << 3;
    /** Out: position or velocity may have changed; write back to the entity. */
    public static final int MOVED = 1 << 4;
    /** Out: the resting throttle skipped the move this tick (gravity still applied). */
    public static final int HELD = 1 << 5;
    /** State: vanilla {@code onGroundNoBlocks}. */
    public static final int GROUND_NO_BLOCKS = 1 << 6;
    /** In: ItemEntity rule -- a resting body moves only every 4th tick. */
    public static final int THROTTLE_RESTING = 1 << 8;

    public static final int OUT_MASK = H_COLLISION | V_COLLISION | DEFERRED | MOVED | HELD;

    /** Bits 24-25: {@code (tickCount + id) & 3} for the next step; advanced by every step. */
    public static final int PHASE_SHIFT = 24;
    public static final int PHASE_MASK = 3 << PHASE_SHIFT;

    private BodyFlags() {
    }

    public static int phase(int flags) {
        return (flags >>> PHASE_SHIFT) & 3;
    }

    public static int withPhase(int flags, int phase) {
        return (flags & ~PHASE_MASK) | ((phase & 3) << PHASE_SHIFT);
    }
}
