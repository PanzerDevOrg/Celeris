package com.panzer.mods.celeris.physics;

import static com.panzer.mods.celeris.physics.BodyLayout.CHUNK;
import static com.panzer.mods.celeris.physics.BodyLayout.PARALLEL_THRESHOLD;
import static com.panzer.mods.celeris.physics.BodyLayout.RESULT_STRIDE;
import static com.panzer.mods.celeris.physics.BodyLayout.roundCapacity;

/**
 * {@link BodyBatch} on plain Java arrays: the engine used when the JVM has no
 * FFM (Minecraft 1.21.x without {@code --enable-preview}) or the native
 * library is missing. Same Structure-of-Arrays layout as the off-heap slab
 * -- one array per column, all allocated here, nothing allocated per tick --
 * and the same chunked parallel step on {@link ChunkWorkers}, run by
 * {@link JavaPhysicsKernel}, the reference implementation the native kernel
 * is tested against.
 */
final class HeapBodyBatch implements BodyBatch, ChunkWorkers.ChunkTask {

    /** Chunk results are written 64 bytes apart so workers never share a line. */

    private final PhysicsMode mode;
    private final int capacity;
    final double[] px, py, pz, vx, vy, vz, halfWidth, height, gravity, dragAirH, dragV, groundScale, belowOffset,
            factorH, factorV;
    final int[] flags, deferredList, cellHash, sorted, buckets;
    private final int[] pairs;
    private final int pairCapacity;
    private final int[] chunkDeferred;
    private int size;
    private int deferredCount;

    private HeapTerrain stepTerrain;
    private int stepMode;
    /** Bodies per work unit of the current step ({@link BodyLayout#unit}). */
    private int stepUnit = CHUNK;

    HeapBodyBatch(int requestedCapacity, PhysicsMode mode, int pairCapacity) {
        this.mode = mode;
        this.capacity = roundCapacity(requestedCapacity);
        int c = capacity;
        px = new double[c];
        py = new double[c];
        pz = new double[c];
        vx = new double[c];
        vy = new double[c];
        vz = new double[c];
        halfWidth = new double[c];
        height = new double[c];
        gravity = new double[c];
        dragAirH = new double[c];
        dragV = new double[c];
        groundScale = new double[c];
        belowOffset = new double[c];
        factorH = new double[c];
        factorV = new double[c];
        java.util.Arrays.fill(factorH, 1.0);
        java.util.Arrays.fill(factorV, 1.0);
        flags = new int[c];
        deferredList = new int[c];
        cellHash = new int[c];
        sorted = new int[c];
        buckets = new int[BodyLayout.bucketCount(c) + 1];
        this.pairCapacity = Math.max(0, pairCapacity);
        this.pairs = new int[2 * this.pairCapacity];
        this.chunkDeferred = new int[BodyLayout.resultSlots(c)];
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
        return JavaPhysicsKernel.NAME;
    }

    @Override
    public int add(BodyParams params, float width, float height, double x, double y, double z,
                   double vx, double vy, double vz, boolean onGround, int phase) {
        if (size == capacity) {
            throw new IllegalStateException("batch full (" + capacity + ")");
        }
        int slot = size++;
        gravity[slot] = params.gravity();
        dragAirH[slot] = params.dragAirHorizontal();
        dragV[slot] = params.dragVertical();
        groundScale[slot] = params.groundScale();
        belowOffset[slot] = params.belowOffset();
        factorH[slot] = 1.0;
        factorV[slot] = 1.0;
        setDimensions(slot, width, height);
        setPosition(slot, x, y, z);
        setVelocity(slot, vx, vy, vz);
        flags[slot] = params.throttleResting() ? BodyFlags.THROTTLE_RESTING : 0;
        setState(slot, onGround, false, phase);
        return slot;
    }

    @Override
    public int remove(int slot) {
        if (slot < 0 || slot >= size) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + size);
        }
        int last = --size;
        if (slot == last) {
            return -1;
        }
        px[slot] = px[last];
        py[slot] = py[last];
        pz[slot] = pz[last];
        vx[slot] = vx[last];
        vy[slot] = vy[last];
        vz[slot] = vz[last];
        halfWidth[slot] = halfWidth[last];
        height[slot] = height[last];
        gravity[slot] = gravity[last];
        dragAirH[slot] = dragAirH[last];
        dragV[slot] = dragV[last];
        groundScale[slot] = groundScale[last];
        belowOffset[slot] = belowOffset[last];
        factorH[slot] = factorH[last];
        factorV[slot] = factorV[last];
        flags[slot] = flags[last];
        return last;
    }

    @Override
    public double x(int slot) {
        return px[slot];
    }

    @Override
    public double y(int slot) {
        return py[slot];
    }

    @Override
    public double z(int slot) {
        return pz[slot];
    }

    @Override
    public double vx(int slot) {
        return vx[slot];
    }

    @Override
    public double vy(int slot) {
        return vy[slot];
    }

    @Override
    public double vz(int slot) {
        return vz[slot];
    }

    @Override
    public double halfWidth(int slot) {
        return halfWidth[slot];
    }

    @Override
    public double height(int slot) {
        return height[slot];
    }

    @Override
    public int flags(int slot) {
        return flags[slot];
    }

    @Override
    public void setPosition(int slot, double x, double y, double z) {
        px[slot] = x;
        py[slot] = y;
        pz[slot] = z;
    }

    @Override
    public void setVelocity(int slot, double vx, double vy, double vz) {
        this.vx[slot] = vx;
        this.vy[slot] = vy;
        this.vz[slot] = vz;
    }

    @Override
    public void setState(int slot, boolean onGround, boolean groundNoBlocks, int phase) {
        int f = flags[slot] & BodyFlags.THROTTLE_RESTING;
        f |= onGround ? BodyFlags.ON_GROUND : 0;
        f |= groundNoBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0;
        flags[slot] = BodyFlags.withPhase(f, phase);
    }

    @Override
    public void setDimensions(int slot, float width, float height) {
        // EntityDimensions.makeBoundingBox: float f = width / 2.0F; ... y + (double) height
        halfWidth[slot] = width / 2.0F;
        this.height[slot] = height;
    }

    @Override
    public int step(TerrainView terrain, int rules) {
        stepTerrain = (HeapTerrain) terrain;
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
        int total = 0;
        for (int c = 0; c < chunks; c++) {
            int d = chunkDeferred[c * RESULT_STRIDE];
            int from = c * stepUnit;
            if (from != total) {
                System.arraycopy(deferredList, from, deferredList, total, d);
            }
            total += d;
        }
        deferredCount = total;
        stepTerrain = null;
        return total;
    }

    @Override
    public void run(int chunk) {
        int begin = chunk * stepUnit;
        int end = Math.min(begin + stepUnit, size);
        chunkDeferred[chunk * RESULT_STRIDE] = JavaPhysicsKernel.step(this, stepTerrain, begin, end, stepMode);
    }

    @Override
    public int deferred(int k) {
        return deferredList[k];
    }

    @Override
    public int deferredCount() {
        return deferredCount;
    }

    @Override
    public int broadphase(double margin) {
        return JavaPhysicsKernel.broadphase(this, size, margin, pairs, pairCapacity);
    }

    @Override
    public int pairCapacity() {
        return pairCapacity;
    }

    @Override
    public int pairA(int k) {
        return pairs[2 * k];
    }

    @Override
    public int pairB(int k) {
        return pairs[2 * k + 1];
    }

    @Override
    public void close() {
        size = 0;
    }
}
