package com.panzer.mods.celeris.physics;

/**
 * Keeps a set of heap objects (Minecraft entities) and their off-heap bodies
 * in sync, one tick at a time, without allocating.
 *
 * <p><b>Ownership model.</b> Vanilla state lives in immutable {@code Vec3}
 * fields scattered over the heap, so there is no zero-copy view of it; a full
 * gather + scatter every tick would pay one cache miss per entity twice. The
 * bridge instead makes the batch the source of truth for the simulated
 * fields of a registered entity:
 *
 * <ul>
 *   <li><b>Sync in</b> only what changed outside the engine: code that writes
 *       an entity's position/velocity (teleports, knockback, commands,
 *       other mods -- hooked by a mixin on the setters) calls {@link
 *       #markDirty}; the next {@link #tick} re-reads just those, walking a
 *       bitset a 64-bit word at a time.</li>
 *   <li><b>Simulate</b> the whole batch off-heap ({@link BodyBatch#step}).</li>
 *   <li><b>Vanilla fallback</b> for deferred bodies: their entity still holds
 *       the pre-tick state (nothing was written back), so the accessor runs
 *       the vanilla movement code on it and the result is read back in.</li>
 *   <li><b>Sync out</b> only bodies the kernel flagged {@link BodyFlags#MOVED}:
 *       one sequential pass over the flags column, one write per moved
 *       entity.</li>
 * </ul>
 *
 * <p>The slot of each entity is stored on the entity itself (a mixin-added
 * int field behind {@link Accessor#slot}/{@link Accessor#setSlot}), so lookup
 * is O(1) with no map. Entities removed during a tick (e.g. by their own
 * vanilla fallback) are unlinked after the tick, so slot indices stay stable
 * while it runs.
 *
 * @param <E> entity type
 */
public final class EntityBodyBridge<E> implements AutoCloseable {

    /** Glue between the bridge and one entity type. Implementations must not allocate per call. */
    public interface Accessor<E> {

        /** Slot stored on the entity, -1 when not registered. */
        int slot(E entity);

        void setSlot(E entity, int slot);

        BodyParams params(E entity);

        /** Copies the entity's current state into {@code slot} (position, velocity, state bits, dimensions). */
        void read(E entity, BodyBatch batch, int slot);

        /**
         * Applies the simulated state to the entity: position, velocity, and
         * the collision flags ({@code onGround}, {@code horizontalCollision},
         * {@code verticalCollision}), plus whatever the entity type derives
         * from them (fall distance, {@code hasImpulse}).
         */
        void write(E entity, BodyBatch batch, int slot, int flags);

        /** Runs vanilla movement for a body the kernel deferred this tick. */
        void tickVanilla(E entity);
    }

    private final BodyBatch batch;
    private final Accessor<E> accessor;
    private final Object[] owners;
    private final long[] dirty;
    private final long[] pendingRemoval;
    private boolean ticking;
    private int pendingRemovals;

    public EntityBodyBridge(BodyBatch batch, Accessor<E> accessor) {
        this.batch = batch;
        this.accessor = accessor;
        this.owners = new Object[batch.capacity()];
        this.dirty = new long[(batch.capacity() + 63) >>> 6];
        this.pendingRemoval = new long[dirty.length];
    }

    public BodyBatch batch() {
        return batch;
    }

    public int size() {
        return batch.size();
    }

    public boolean isFull() {
        return batch.size() == batch.capacity();
    }

    /** Starts simulating {@code entity}. Returns false if the batch is full (the entity stays vanilla). */
    public boolean register(E entity) {
        if (accessor.slot(entity) >= 0 || isFull()) {
            return false;
        }
        BodyParams p = accessor.params(entity);
        int slot = batch.add(p, 0f, 0f, 0, 0, 0, 0, 0, 0, false, 0);
        owners[slot] = entity;
        accessor.setSlot(entity, slot);
        accessor.read(entity, batch, slot);
        return true;
    }

    /** Stops simulating {@code entity}; its heap state is current as of the last tick. */
    public void unregister(E entity) {
        int slot = accessor.slot(entity);
        if (slot < 0) {
            return;
        }
        if (ticking) {
            if (!get(pendingRemoval, slot)) {
                set(pendingRemoval, slot);
                pendingRemovals++;
            }
            return;
        }
        unlink(slot);
    }

    /** The entity's position/velocity was written outside the engine; re-read it before the next step. */
    public void markDirty(E entity) {
        int slot = accessor.slot(entity);
        if (slot >= 0) {
            set(dirty, slot);
        }
    }

    /**
     * One tick: sync in dirty entities, simulate, run vanilla for deferred
     * bodies, sync out moved ones. Returns the number of deferred bodies.
     */
    @SuppressWarnings("unchecked")
    public int tick(TerrainView terrain, int rules) {
        ticking = true;
        try {
            for (int w = 0; w < dirty.length; w++) {
                long bits = dirty[w];
                dirty[w] = 0;
                while (bits != 0) {
                    int slot = (w << 6) + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    if (slot < batch.size()) {
                        accessor.read((E) owners[slot], batch, slot);
                    }
                }
            }

            int deferred = batch.step(terrain, rules);

            for (int k = 0; k < deferred; k++) {
                int slot = batch.deferred(k);
                E entity = (E) owners[slot];
                accessor.tickVanilla(entity);
                if (!get(pendingRemoval, slot)) {
                    accessor.read(entity, batch, slot);
                }
            }

            int n = batch.size();
            for (int slot = 0; slot < n; slot++) {
                int f = batch.flags(slot);
                if ((f & (BodyFlags.MOVED | BodyFlags.DEFERRED)) == BodyFlags.MOVED && !get(pendingRemoval, slot)) {
                    accessor.write((E) owners[slot], batch, slot, f);
                }
            }
            return deferred;
        } finally {
            ticking = false;
            if (pendingRemovals > 0) {
                flushRemovals();
            }
        }
    }

    private void flushRemovals() {
        // Highest slot first: swap-remove only ever moves the current last
        // body, which is then never a pending slot we have yet to visit.
        for (int w = pendingRemoval.length - 1; w >= 0; w--) {
            long bits = pendingRemoval[w];
            pendingRemoval[w] = 0;
            while (bits != 0) {
                int bit = 63 - Long.numberOfLeadingZeros(bits);
                bits &= ~(1L << bit);
                unlink((w << 6) + bit);
            }
        }
        pendingRemovals = 0;
    }

    @SuppressWarnings("unchecked")
    private void unlink(int slot) {
        E leaving = (E) owners[slot];
        int last = batch.size() - 1;
        int moved = batch.remove(slot);
        accessor.setSlot(leaving, -1);
        clear(dirty, slot);
        if (moved >= 0) {
            E mover = (E) owners[moved];
            owners[slot] = mover;
            accessor.setSlot(mover, slot);
            if (get(dirty, moved)) {
                set(dirty, slot);
            }
            clear(dirty, moved);
        }
        owners[last] = null;
    }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << i)) != 0;
    }

    private static void set(long[] bits, int i) {
        bits[i >>> 6] |= 1L << i;
    }

    private static void clear(long[] bits, int i) {
        bits[i >>> 6] &= ~(1L << i);
    }

    @Override
    public void close() {
        batch.close();
    }
}
