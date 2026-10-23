package com.panzer.mods.celeris.physics;

import static com.panzer.mods.celeris.physics.BodyLayout.*;
import static com.panzer.mods.celeris.physics.RawMemory.UNSAFE;

/**
 * {@link BodyBatch} over one 64-byte-aligned off-heap slab, stepped by the
 * native kernel through JNI. Same layout and the same chunked parallel step as
 * {@code SegmentBodyBatch}; memory is reached with {@code Unsafe} instead of a
 * {@code MemorySegment}, so no FFM is needed. Slots are bounds-checked against
 * {@link #limit}, which {@link #close} drops to 0, so a stale or out-of-range
 * access throws instead of touching freed memory. Nothing below allocates
 * after construction.
 */
@SuppressWarnings("removal")
final class JniBodyBatch implements BodyBatch, ChunkWorkers.ChunkTask {

    private final PhysicsMode mode;
    private final int capacity;
    /** {@code capacity} while open, 0 once closed: the bound every slot access checks. */
    private int limit;
    private final RawMemory slab;
    private final RawMemory buckets;
    private final RawMemory pairs;
    private final long base;
    private final int bucketInts;
    private final int pairCapacity;
    private final int[] chunkDeferred;
    private int size;
    private int deferredCount;

    // Per-step state read by run(chunk) on worker threads; published by
    // ChunkWorkers' volatile generation write.
    private JniTerrain stepTerrain;
    private int stepMode;
    /** Bodies per work unit of the current step ({@link BodyLayout#unit}). */
    private int stepUnit = CHUNK;

    JniBodyBatch(int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        this.mode = mode;
        this.capacity = roundCapacity(requestedCapacity);
        this.limit = capacity;
        this.slab = RawMemory.allocate(slabBytes(capacity), ALIGNMENT);
        this.base = slab.address;
        this.bucketInts = bucketCount(capacity) + 1;
        this.buckets = RawMemory.allocate((long) bucketInts * Integer.BYTES, ALIGNMENT);
        this.pairCapacity = Math.max(0, pairCapacity);
        this.pairs = RawMemory.allocate(Math.max(8L, 8L * this.pairCapacity), ALIGNMENT);
        this.chunkDeferred = new int[BodyLayout.resultSlots(capacity)];
        // Padding lanes must stay inert for the SIMD passes.
        for (int i = 0; i < capacity; i++) {
            UNSAFE.putDouble(f64(FACTOR_H, i), 1.0);
            UNSAFE.putDouble(f64(FACTOR_V, i), 1.0);
        }
    }

    private long f64(int column, int slot) {
        if (Integer.compareUnsigned(slot, limit) >= 0) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + capacity + (limit == 0 ? " (closed)" : ""));
        }
        return base + f64Offset(column, capacity) + ((long) slot << 3);
    }

    private long u32(int column, int slot) {
        if (Integer.compareUnsigned(slot, limit) >= 0) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + capacity + (limit == 0 ? " (closed)" : ""));
        }
        return base + u32Offset(column, capacity) + ((long) slot << 2);
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
        return JniPhysicsKernel.name();
    }

    private double get(int column, int slot) {
        return UNSAFE.getDouble(f64(column, slot));
    }

    private void put(int column, int slot, double value) {
        UNSAFE.putDouble(f64(column, slot), value);
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
        UNSAFE.putInt(u32(FLAGS, slot), params.throttleResting() ? BodyFlags.THROTTLE_RESTING : 0);
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
        UNSAFE.putInt(u32(FLAGS, slot), UNSAFE.getInt(u32(FLAGS, last)));
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
        return UNSAFE.getInt(u32(FLAGS, slot));
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
        long at = u32(FLAGS, slot);
        int f = UNSAFE.getInt(at) & BodyFlags.THROTTLE_RESTING;
        f |= onGround ? BodyFlags.ON_GROUND : 0;
        f |= groundNoBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0;
        UNSAFE.putInt(at, BodyFlags.withPhase(f, phase));
    }

    @Override
    public void setDimensions(int slot, float width, float height) {
        // EntityDimensions.makeBoundingBox: float f = width / 2.0F; ... y + (double) height
        put(HALF_WIDTH, slot, width / 2.0F);
        put(HEIGHT, slot, height);
    }

    @Override
    public int step(TerrainView terrain, int rules) {
        if (limit == 0) {
            throw new IllegalStateException("batch closed");
        }
        JniTerrain t = (JniTerrain) terrain;
        t.checkOpen();
        stepTerrain = t;
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
        long list = base + u32Offset(DEFERRED_LIST, capacity);
        int total = 0;
        for (int c = 0; c < chunks; c++) {
            int d = chunkDeferred[c * RESULT_STRIDE];
            int from = c * stepUnit;
            if (from != total) {
                for (int k = 0; k < d; k++) {
                    UNSAFE.putInt(list + ((long) (total + k) << 2), UNSAFE.getInt(list + ((long) (from + k) << 2)));
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
        chunkDeferred[chunk * RESULT_STRIDE] =
                JniPhysicsKernel.step(base, capacity, stepTerrain.header(), begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        if (k < 0 || k >= deferredCount) {
            throw new IndexOutOfBoundsException("deferred " + k + " of " + deferredCount);
        }
        return UNSAFE.getInt(u32(DEFERRED_LIST, k));
    }

    @Override
    public int deferredCount() {
        return deferredCount;
    }

    @Override
    public int broadphase(double margin, boolean stopAtCapacity) {
        if (limit == 0) {
            throw new IllegalStateException("batch closed");
        }
        return JniPhysicsKernel.broadphase(base, capacity, size, buckets.address, bucketInts, margin,
                pairs.address, pairCapacity, stopAtCapacity);
    }

    @Override
    public int pairCapacity() {
        return pairCapacity;
    }

    @Override
    public int pairA(int k) {
        return UNSAFE.getInt(pairAt(k));
    }

    @Override
    public int pairB(int k) {
        return UNSAFE.getInt(pairAt(k) + Integer.BYTES);
    }

    private long pairAt(int k) {
        if (Integer.compareUnsigned(k, limit == 0 ? 0 : pairCapacity) >= 0) {
            throw new IndexOutOfBoundsException("pair " + k + " of " + pairCapacity);
        }
        return pairs.address + ((long) k << 3);
    }

    @Override
    public void close() {
        if (limit == 0) {
            return;
        }
        limit = 0;
        slab.free();
        buckets.free();
        pairs.free();
    }
}
