package com.panzer.mods.celeris.physics;

/**
 * The pure-Java engine: batches and terrain on Java arrays, run by {@link
 * JavaPhysicsKernel}. Needs no FFM, no native library and no JVM flags, so it
 * is always available; {@link CelerisPhysics} picks it whenever the native
 * kernel cannot be used.
 */
public final class HeapPhysicsFactory implements PhysicsFactory {

    @Override
    public String engineName() {
        return JavaPhysicsKernel.NAME;
    }

    @Override
    public BodyBatch newBatch(int capacity, PhysicsMode mode, int pairCapacity) {
        return new HeapBodyBatch(capacity, mode, pairCapacity);
    }

    @Override
    public TerrainView newTerrain(int sizeX, int sizeY, int sizeZ) {
        return new HeapTerrain(sizeX, sizeY, sizeZ);
    }
}
