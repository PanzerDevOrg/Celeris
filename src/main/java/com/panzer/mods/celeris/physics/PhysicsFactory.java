package com.panzer.mods.celeris.physics;

/**
 * Creates batches and terrain for the engine selected at startup.
 * Implemented in the {@code ffm} source set and reached only reflectively
 * through {@link CelerisPhysics}.
 */
public interface PhysicsFactory {

    String engineName();

    BodyBatch newBatch(int capacity, PhysicsMode mode, int pairCapacity);

    TerrainView newTerrain(int sizeX, int sizeY, int sizeZ);
}
