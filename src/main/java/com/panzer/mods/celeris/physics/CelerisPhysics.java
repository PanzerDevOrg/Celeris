package com.panzer.mods.celeris.physics;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the batch physics engine. Selected once per JVM, like
 * {@link CelerisRuntime} and the SIMD runtime:
 *
 * <ol>
 *   <li>{@code native}: the C++ kernel (AVX-512 / AVX2+FMA / NEON / generic,
 *       chosen from CPUID) through the FFM {@code Linker}.</li>
 *   <li>{@code java+simd}: the Java reference kernel with its streaming passes
 *       on {@code jdk.incubator.vector}.</li>
 *   <li>{@code java}: the Java reference kernel, scalar.</li>
 *   <li>unavailable: no FFM on this JVM (Java 21 without
 *       {@code --enable-preview}) or compat mode. {@link #isAvailable()} is
 *       false and callers keep the vanilla tick: physics has no heap
 *       fallback, because a heap SoA would be slower than the vanilla code it
 *       replaces.</li>
 * </ol>
 *
 * <p>{@code -Dceleris.physics.engine=java} skips the native kernel, {@code
 * =off} disables the engine; {@code -Dceleris.physics.isa=generic|avx2|avx512|neon}
 * pins the native ISA.
 */
public final class CelerisPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Physics");
    private static final String FACTORY_CLASS = "com.panzer.mods.celeris.physics.SegmentPhysicsFactory";

    private static volatile PhysicsFactory factory;
    private static volatile String unavailableReason;
    private static volatile ChunkWorkers workers;

    private CelerisPhysics() {
    }

    public static boolean isAvailable() {
        return factory() != null;
    }

    /** Engine name, or {@code "unavailable (<reason>)"}. */
    public static String engineName() {
        PhysicsFactory f = factory();
        return f != null ? f.engineName() : "unavailable (" + unavailableReason + ")";
    }

    public static BodyBatch newBatch(int capacity, PhysicsMode mode) {
        return newBatch(capacity, mode, capacity * 4);
    }

    public static BodyBatch newBatch(int capacity, PhysicsMode mode, int pairCapacity) {
        return require().newBatch(capacity, mode, pairCapacity);
    }

    public static TerrainView newTerrain(int sizeX, int sizeY, int sizeZ) {
        return require().newTerrain(sizeX, sizeY, sizeZ);
    }

    /** Shared worker pool for {@link BodyBatch#step}, started on first use. */
    public static ChunkWorkers workers() {
        ChunkWorkers w = workers;
        if (w == null) {
            synchronized (CelerisPhysics.class) {
                w = workers;
                if (w == null) {
                    int threads = Integer.getInteger("celeris.physics.threads",
                            Math.max(0, Math.min(8, Runtime.getRuntime().availableProcessors() / 2 - 1)));
                    workers = w = new ChunkWorkers(threads);
                }
            }
        }
        return w;
    }

    private static PhysicsFactory require() {
        PhysicsFactory f = factory();
        if (f == null) {
            throw new IllegalStateException("Celeris physics engine unavailable: " + unavailableReason);
        }
        return f;
    }

    private static PhysicsFactory factory() {
        PhysicsFactory f = factory;
        if (f != null || unavailableReason != null) {
            return f;
        }
        synchronized (CelerisPhysics.class) {
            if (factory == null && unavailableReason == null) {
                select();
            }
            return factory;
        }
    }

    private static void select() {
        String engine = System.getProperty("celeris.physics.engine", "auto");
        if ("off".equalsIgnoreCase(engine)) {
            unavailableReason = "disabled by -Dceleris.physics.engine=off";
        } else if (CelerisRuntime.isCompatModeForced()) {
            unavailableReason = "compat mode";
        } else if (!CelerisRuntime.isFfmActive()) {
            unavailableReason = "FFM not available on this JVM";
        } else {
            try {
                factory = (PhysicsFactory) Class.forName(FACTORY_CLASS).getDeclaredConstructor().newInstance();
                LOGGER.info("Celeris physics engine: {}", factory.engineName());
                return;
            } catch (Throwable t) {
                unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        LOGGER.info("Celeris physics engine unavailable ({}) -- vanilla entity physics stays in charge", unavailableReason);
    }
}
