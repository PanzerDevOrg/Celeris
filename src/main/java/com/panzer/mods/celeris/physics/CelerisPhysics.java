package com.panzer.mods.celeris.physics;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the batch physics engine. Selected once per JVM, like
 * {@link CelerisRuntime} and the SIMD runtime, and always available unless
 * turned off -- no JVM flags are required:
 *
 * <ol>
 *   <li>{@code native}: the C++ kernel (AVX2 / AVX-512 / NEON / generic,
 *       chosen from CPUID) on off-heap memory, through the FFM {@code Linker}.
 *       Needs FFM: Java 22+ (Minecraft 26.x), or Java 21 with
 *       {@code --enable-preview} (Minecraft 1.21.x).</li>
 *   <li>{@code java}: the pure-Java reference kernel on Java arrays
 *       ({@link HeapPhysicsFactory}). Same results, same parallel chunking;
 *       used whenever the native kernel cannot be (no FFM, no library for
 *       this platform, compat mode).</li>
 * </ol>
 *
 * <p>{@code -Dceleris.physics.engine=java} forces the Java kernel, {@code
 * =off} disables the engine ({@link #isAvailable()} false: callers keep
 * vanilla code); {@code -Dceleris.physics.isa=generic|avx2|avx512|neon} pins
 * the native ISA.
 */
public final class CelerisPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Physics");
    private static final String NATIVE_FACTORY_CLASS = "com.panzer.mods.celeris.physics.SegmentPhysicsFactory";

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
            LOGGER.info("Celeris physics engine disabled (-Dceleris.physics.engine=off)");
            return;
        }
        String skipped = null;
        if ("java".equalsIgnoreCase(engine)) {
            skipped = "-Dceleris.physics.engine=java";
        } else if (CelerisRuntime.isCompatModeForced()) {
            skipped = "compat mode";
        } else if (!CelerisRuntime.isFfmActive()) {
            skipped = "FFM not available; on Java 21 add --enable-preview --enable-native-access=ALL-UNNAMED";
        } else {
            try {
                factory = (PhysicsFactory) Class.forName(NATIVE_FACTORY_CLASS).getDeclaredConstructor().newInstance();
                LOGGER.info("Celeris physics engine: {}", factory.engineName());
                return;
            } catch (Throwable t) {
                skipped = "native kernel unavailable: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        factory = new HeapPhysicsFactory();
        LOGGER.info("Celeris physics engine: {} ({})", factory.engineName(), skipped);
    }
}
