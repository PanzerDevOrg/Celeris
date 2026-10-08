package com.panzer.mods.celeris.physics;

/**
 * A dense batch of simulated bodies, Structure-of-Arrays (see {@link
 * BodyLayout}): off-heap for the native engine, on Java arrays for the
 * pure-Java one.
 * Slots {@code [0, size())} are always live: {@link #remove} swap-removes, so
 * kernels never test an "active" mask.
 *
 * <p>No method allocates on the Java heap. Not thread-safe: one owner thread
 * (normally the level's tick thread) calls everything; {@link #step} fans out
 * to worker threads internally and joins before returning.
 */
public interface BodyBatch extends AutoCloseable {

    int capacity();

    int size();

    PhysicsMode mode();

    /** Name of the kernel behind this batch, e.g. {@code native-avx2}. */
    String engineName();

    /**
     * Appends a body and returns its slot. {@code width}/{@code height} are the
     * entity's float dimensions; the box is built like {@code
     * EntityDimensions.makeBoundingBox}. {@code phase} is {@code (tickCount + id) & 3}
     * as of the next tick (only read when {@code params.throttleResting()}).
     */
    int add(BodyParams params, float width, float height, double x, double y, double z,
            double vx, double vy, double vz, boolean onGround, int phase);

    /**
     * Removes the body in {@code slot} by moving the last body into it.
     *
     * @return the former slot of the body that now lives in {@code slot}, or
     *         -1 if {@code slot} was the last one (nothing moved)
     */
    int remove(int slot);

    double x(int slot);

    double y(int slot);

    double z(int slot);

    double vx(int slot);

    double vy(int slot);

    double vz(int slot);

    /** Half the box width, as {@code (double) (width / 2.0F)}. */
    double halfWidth(int slot);

    double height(int slot);

    int flags(int slot);

    void setPosition(int slot, double x, double y, double z);

    void setVelocity(int slot, double vx, double vy, double vz);

    /** Replaces the state bits ({@link BodyFlags#ON_GROUND}, {@link BodyFlags#GROUND_NO_BLOCKS}, phase). */
    void setState(int slot, boolean onGround, boolean groundNoBlocks, int phase);

    void setDimensions(int slot, float width, float height);

    /**
     * Simulates one tick against {@code terrain}. Returns the number of
     * deferred bodies (see {@link #deferred}). Large batches are split into
     * work units of {@link BodyLayout#MIN_UNIT} to {@link BodyLayout#CHUNK} bodies
     * ({@link BodyLayout#unit}) run on {@link ChunkWorkers}.
     *
     * @param rules extra rule bits, e.g. {@link PhysicsMode#RULE_SMALL_MOVES}
     */
    int step(TerrainView terrain, int rules);

    /** Deferred body slots of the last {@link #step}, {@code k < deferredCount()}. */
    int deferred(int k);

    int deferredCount();

    /**
     * Finds the pairs of bodies whose boxes overlap after inflating by
     * {@code margin} (e.g. item merge candidates, push pairs), stopping once
     * {@link #pairCapacity()} pairs are stored: {@code broadphase(margin, true)}.
     *
     * <p>Truncation: pairs come in a fixed order (by the first body's slot,
     * then by spatial-hash bucket), identical for the Java and native kernels.
     * The result is {@code min(total, pairCapacity() + 1)}; a result above
     * {@code pairCapacity()} means the list was cut and more pairs exist, and
     * the stored pairs are then exactly the first {@code pairCapacity()} of the
     * full list. Stopping keeps the cost proportional to the pairs kept: a pile
     * of n items in one block has about n&sup2;/2 overlapping pairs, which a
     * full count would visit every time.
     */
    default int broadphase(double margin) {
        return broadphase(margin, true);
    }

    /**
     * {@link #broadphase(double)}, or with {@code stopAtCapacity == false} a
     * full count: the scan visits every overlapping pair and returns the total,
     * which may exceed {@link #pairCapacity()} (only that many are stored).
     */
    int broadphase(double margin, boolean stopAtCapacity);

    int pairCapacity();

    int pairA(int k);

    int pairB(int k);

    @Override
    void close();
}
