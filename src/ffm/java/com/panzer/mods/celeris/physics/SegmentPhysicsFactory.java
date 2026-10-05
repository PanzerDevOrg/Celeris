package com.panzer.mods.celeris.physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the kernel once (reached reflectively from {@link CelerisPhysics}):
 * native when the bundled library loads and matches the Java layout, else the
 * Java reference kernel with Vector API streaming passes when {@code
 * jdk.incubator.vector} is enabled, else the scalar Java kernel.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class SegmentPhysicsFactory implements PhysicsFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Physics");
    private static final String SIMD_STREAMS_CLASS = "com.panzer.mods.celeris.physics.SimdStreamKernel";

    private final PhysicsKernel kernel;

    public SegmentPhysicsFactory() {
        this.kernel = select();
    }

    SegmentPhysicsFactory(PhysicsKernel kernel) {
        this.kernel = kernel;
    }

    private static PhysicsKernel select() {
        if (!"java".equalsIgnoreCase(System.getProperty("celeris.physics.engine", "auto"))) {
            try {
                return new NativePhysicsKernel();
            } catch (Throwable t) {
                LOGGER.info("Celeris physics: native kernel unavailable ({}: {}) -- using the Java kernel",
                        t.getClass().getSimpleName(), t.getMessage());
            }
        }
        return new ScalarPhysicsKernel(loadStreams());
    }

    static StreamKernel loadStreams() {
        try {
            StreamKernel simd = (StreamKernel) Class.forName(SIMD_STREAMS_CLASS).getDeclaredConstructor().newInstance();
            simd.name(); // links jdk.incubator.vector now, not mid-tick
            return simd;
        } catch (Throwable t) {
            return null;
        }
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
