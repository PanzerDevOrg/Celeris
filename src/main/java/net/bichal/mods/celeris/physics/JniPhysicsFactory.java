package net.bichal.mods.celeris.physics;

/**
 * The JNI engine: off-heap batches and terrain run by the C++ kernel through
 * {@link JniPhysicsKernel}. Reached reflectively from {@link CelerisPhysics};
 * the constructor throws if the bundled library cannot be loaded or Unsafe
 * memory access is unavailable, and {@link CelerisPhysics} then tries FFM or
 * falls back to {@link HeapPhysicsFactory}.
 */
public final class JniPhysicsFactory implements PhysicsFactory {

    private final String name;

    public JniPhysicsFactory() {
        this.name = JniPhysicsKernel.name(); // loads the library
    }

    @Override
    public String engineName() {
        return name;
    }

    @Override
    public BodyBatch newBatch(int capacity, PhysicsMode mode, int pairCapacity) {
        return new JniBodyBatch(capacity, mode, pairCapacity);
    }

    @Override
    public TerrainView newTerrain(int sizeX, int sizeY, int sizeZ) {
        return new JniTerrain(sizeX, sizeY, sizeZ);
    }
}
