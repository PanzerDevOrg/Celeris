package com.panzer.mods.celeris.physics;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.panzer.mods.celeris.physics.BodyLayout.*;

/**
 * {@link BodyBatch} over one 64-byte-aligned slab from {@link Arena#ofShared()}
 * (shared: worker threads read and write it during {@link #step}). The slab,
 * the broadphase buckets and the pair buffer are allocated once here;
 * nothing below allocates.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SegmentBodyBatch implements BodyBatch, ChunkWorkers.ChunkTask {

    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;
    /** Chunk results are written 64 bytes apart so workers never share a line. */
    /** Below this many bodies a step runs on the calling thread: waking workers costs more. */

    private final Arena arena = Arena.ofShared();
    private final PhysicsKernel kernel;
    private final PhysicsMode mode;
    private final int capacity;
    final MemorySegment slab;
    private final MemorySegment buckets;
    private final MemorySegment pairs;
    private final int pairCapacity;
    private final int[] chunkDeferred;
    private int size;
    private int deferredCount;
    private int pairCount;

    // Per-step state read by run(chunk) on worker threads; published by
    // ChunkWorkers' volatile generation write.
    private SegmentTerrain stepTerrain;
    private int stepMode;
    /** Bodies per work unit of the current step ({@link BodyLayout#unit}). */
    private int stepUnit = CHUNK;

    SegmentBodyBatch(PhysicsKernel kernel, int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        this.kernel = kernel;
        this.mode = mode;
        this.capacity = roundCapacity(requestedCapacity);
        this.slab = arena.allocate(slabBytes(capacity), ALIGNMENT);
        this.buckets = arena.allocate((long) (bucketCount(capacity) + 1) * Integer.BYTES, ALIGNMENT);
        this.pairCapacity = Math.max(0, pairCapacity);
        this.pairs = arena.allocate(Math.max(8L, 8L * this.pairCapacity), ALIGNMENT);
        this.chunkDeferred = new int[BodyLayout.resultSlots(capacity)];
        // Padding lanes must stay inert for the SIMD passes.
        for (int i = 0; i < capacity; i++) {
            slab.set(F64, f64Offset(FACTOR_H, capacity) + ((long) i << 3), 1.0);
            slab.set(F64, f64Offset(FACTOR_V, capacity) + ((long) i << 3), 1.0);
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public PhysicsMode mode() {
        return mode;
    }

    @Override
    public String engineName() {
        return kernel.name();
    }

    private double get(int column, int slot) {
        return slab.get(F64, f64Offset(column, capacity) + ((long) slot << 3));
    }

    private void put(int column, int slot, double value) {
        slab.set(F64, f64Offset(column, capacity) + ((long) slot << 3), value);
    }

    private long flagsAt(int slot) {
        return u32Offset(FLAGS, capacity) + ((long) slot << 2);
    }

    @Override
    public int add(BodyParams params, float width, float height, double x, double y, double z,
                   double vx, double vy, double vz, boolean onGround, int phase) {
        if (size == capacity) {
            throw new IllegalStateException("batch full (" + capacity + ")");
        }
        int slot = size++;
        put(GRAVITY, slot, params.gravity());
        put(DRAG_AIR_H, slot, params.dragAirHorizontal());
        put(DRAG_V, slot, params.dragVertical());
        put(GROUND_SCALE, slot, params.groundScale());
        put(BELOW_OFFSET, slot, params.belowOffset());
        put(FACTOR_H, slot, 1.0);
        put(FACTOR_V, slot, 1.0);
        setDimensions(slot, width, height);
        setPosition(slot, x, y, z);
        setVelocity(slot, vx, vy, vz);
        slab.set(U32, flagsAt(slot), params.throttleResting() ? BodyFlags.THROTTLE_RESTING : 0);
        setState(slot, onGround, false, phase);
        return slot;
    }

    @Override
    public int remove(int slot) {
        checkSlot(slot);
        int last = --size;
        if (slot == last) {
            return -1;
        }
        for (int c = 0; c < F64_COLUMNS; c++) {
            put(c, slot, get(c, last));
        }
        slab.set(U32, flagsAt(slot), slab.get(U32, flagsAt(last)));
        return last;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= size) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + size);
        }
    }

    @Override
    public double x(int slot) {
        return get(POS_X, slot);
    }

    @Override
    public double y(int slot) {
        return get(POS_Y, slot);
    }

    @Override
    public double z(int slot) {
        return get(POS_Z, slot);
    }

    @Override
    public double vx(int slot) {
        return get(VEL_X, slot);
    }

    @Override
    public double vy(int slot) {
        return get(VEL_Y, slot);
    }

    @Override
    public double vz(int slot) {
        return get(VEL_Z, slot);
    }

    @Override
    public double halfWidth(int slot) {
        return get(HALF_WIDTH, slot);
    }

    @Override
    public double height(int slot) {
        return get(HEIGHT, slot);
    }

    @Override
    public int flags(int slot) {
        return slab.get(U32, flagsAt(slot));
    }

    @Override
    public void setPosition(int slot, double x, double y, double z) {
        put(POS_X, slot, x);
        put(POS_Y, slot, y);
        put(POS_Z, slot, z);
    }

    @Override
    public void setVelocity(int slot, double vx, double vy, double vz) {
        put(VEL_X, slot, vx);
        put(VEL_Y, slot, vy);
        put(VEL_Z, slot, vz);
    }

    @Override
    public void setState(int slot, boolean onGround, boolean groundNoBlocks, int phase) {
        int f = slab.get(U32, flagsAt(slot)) & BodyFlags.THROTTLE_RESTING;
        f |= onGround ? BodyFlags.ON_GROUND : 0;
        f |= groundNoBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0;
        slab.set(U32, flagsAt(slot), BodyFlags.withPhase(f, phase));
    }

    @Override
    public void setDimensions(int slot, float width, float height) {
        // EntityDimensions.makeBoundingBox: float f = width / 2.0F; ... y + (double) height
        put(HALF_WIDTH, slot, width / 2.0F);
        put(HEIGHT, slot, height);
    }

    @Override
    public int step(TerrainView terrain, int rules) {
        stepTerrain = (SegmentTerrain) terrain;
        stepMode = mode.nativeMode() | rules;
        ChunkWorkers workers = size >= PARALLEL_THRESHOLD ? CelerisPhysics.workers() : null;
        stepUnit = BodyLayout.unit(size, workers == null ? 0 : workers.threadCount());
        int chunks = (size + stepUnit - 1) / stepUnit;
        // The pool is shared: if another batch is stepping on it (another thread),
        // this one runs its chunks itself, with the same results.
        if (workers == null || !workers.tryRun(this, chunks)) {
            for (int c = 0; c < chunks; c++) {
                run(c);
            }
        }
        // Compact the per-chunk deferred lists (each written from its chunk's
        // first slot) into one run at the start of the column.
        long list = u32Offset(DEFERRED_LIST, capacity);
        int total = 0;
        for (int c = 0; c < chunks; c++) {
            int d = chunkDeferred[c * RESULT_STRIDE];
            int from = c * stepUnit;
            if (from != total) {
                for (int k = 0; k < d; k++) {
                    slab.set(U32, list + ((long) (total + k) << 2), slab.get(U32, list + ((long) (from + k) << 2)));
                }
            }
            total += d;
        }
        deferredCount = total;
        stepTerrain = null;
        return total;
    }

    /** One chunk; called on the stepping thread or a worker. */
    @Override
    public void run(int chunk) {
        int begin = chunk * stepUnit;
        int end = Math.min(begin + stepUnit, size);
        chunkDeferred[chunk * RESULT_STRIDE] = kernel.step(slab, capacity, stepTerrain, begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        return slab.get(U32, u32Offset(DEFERRED_LIST, capacity) + ((long) k << 2));
    }

    @Override
    public int deferredCount() {
        return deferredCount;
    }

    @Override
    public int broadphase(double margin, boolean stopAtCapacity) {
        pairCount = kernel.broadphase(slab, capacity, size, buckets, margin, pairs, pairCapacity, stopAtCapacity);
        return pairCount;
    }

    @Override
    public int pairCapacity() {
        return pairCapacity;
    }

    @Override
    public int pairA(int k) {
        return pairs.getAtIndex(U32, 2L * k);
    }

    @Override
    public int pairB(int k) {
        return pairs.getAtIndex(U32, 2L * k + 1);
    }

    @Override
    public void close() {
        arena.close();
    }
}
