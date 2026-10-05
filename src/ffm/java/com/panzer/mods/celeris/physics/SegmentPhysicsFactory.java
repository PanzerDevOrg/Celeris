package com.panzer.mods.celeris.physics;

/**
 * The native engine: off-heap batches and terrain run by the C++ kernel.
 * Reached reflectively from {@link CelerisPhysics} only when FFM is active;
 * the constructor throws if the bundled library cannot be loaded, and
 * {@link CelerisPhysics} then falls back to {@link HeapPhysicsFactory}.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class SegmentPhysicsFactory implements PhysicsFactory {

    private final PhysicsKernel kernel;

    public SegmentPhysicsFactory() {
        this.kernel = new NativePhysicsKernel();
    }

    @Override
    public String engineName() {
        return kernel.name();
    }

    @Override
    public BodyBatch newBatch(int capacity, PhysicsMode mode, int pairCapacity) {
        return new SegmentBodyBatch(kernel, capacity, mode, pairCapacity);
    }

    @Override
    public TerrainView newTerrain(int sizeX, int sizeY, int sizeZ) {
        return new SegmentTerrain(sizeX, sizeY, sizeZ);
    }

    PhysicsKernel kernel() {
        return kernel;
    }
}
