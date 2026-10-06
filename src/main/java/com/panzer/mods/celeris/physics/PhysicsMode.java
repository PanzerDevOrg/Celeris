package com.panzer.mods.celeris.physics;

/** Integration order of a {@link BodyBatch}. */
public enum PhysicsMode {
    /**
     * Bit-exact {@code Entity.move} parity: gravity, then collide and move,
     * then drag, each as a separate IEEE operation (no FMA). Use for bodies
     * mirrored from vanilla entities.
     */
    VANILLA(0),
    /**
     * Free bodies owned by a mod (debris, particles with collision): the
     * velocity column holds the next tick's movement, and drag plus gravity
     * collapse into one fused multiply-add per lane. Not vanilla-identical.
     */
    FUSED(1);

    /** Rule bit: also apply movements the collision only barely shortened (newer {@code Entity.move}). */
    public static final int RULE_SMALL_MOVES = 1 << 8;

    private final int nativeMode;

    PhysicsMode(int nativeMode) {
        this.nativeMode = nativeMode;
    }

    public int nativeMode() {
        return nativeMode;
    }
}
