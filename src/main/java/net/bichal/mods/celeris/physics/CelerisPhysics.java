package net.bichal.mods.celeris.physics;

import net.bichal.mods.celeris.core.memory.backend.CelerisRuntime;
import java.lang.reflect.InvocationTargetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the batch physics engine. Selected once per JVM, like
 * {@link CelerisRuntime} and the SIMD runtime, and always available unless
 * turned off -- no JVM flags are required:
 *
 * <ol>
 *   <li>{@code jni}: the C++ kernel (AVX2 / AVX-512 / NEON / generic, chosen
 *       from CPUID) on off-heap memory, through JNI. Needs no JVM flag, works
 *       on Java 21 without {@code --enable-preview} and is not blocked by
 *       {@code --enable-native-access}.</li>
 *   <li>{@code ffm}: the same kernel through the FFM {@code Linker}. Tried when
 *       JNI cannot load. Needs FFM: 1.21.x jars need Java 21 with
 *       {@code --enable-preview}; 26.x jars need Java 25.</li>
 *   <li>{@code java}: the pure-Java reference kernel on Java arrays
 *       ({@link HeapPhysicsFactory}). Same results, same parallel chunking;
 *       used whenever the native kernel cannot be (no library for this
 *       platform, compat mode).</li>
 * </ol>
 *
 * <p>{@code -Dceleris.physics.engine=java} forces the Java kernel, {@code
 * =jni} or {@code =ffm} restrict the native choice to that binding (falling
 * back to Java if it cannot load), {@code =off} disables the engine ({@link
 * #isAvailable()} false: callers keep vanilla code); {@code
 * -Dceleris.physics.isa=generic|avx2|avx512|neon} pins the native ISA.
 */
public final class CelerisPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Physics");
    private static final String JNI_FACTORY_CLASS = "net.bichal.mods.celeris.physics.JniPhysicsFactory";
    private static final String FFM_FACTORY_CLASS = "net.bichal.mods.celeris.physics.SegmentPhysicsFactory";

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
        } else {
            StringBuilder failures = new StringBuilder();
            if (!"ffm".equalsIgnoreCase(engine) && load(JNI_FACTORY_CLASS, "jni", failures)) {
                return;
            }
            if (!"jni".equalsIgnoreCase(engine)) {
                if (!CelerisRuntime.isFfmActive()) {
                    append(failures, Runtime.version().feature() == 21
                            ? "ffm: FFM not available; on Java 21 add --enable-preview"
                            : "ffm: FFM not available in this jar on Java " + Runtime.version().feature()
                                    + " (1.21.x jars use FFM only on Java 21 with --enable-preview)");
                } else if (load(FFM_FACTORY_CLASS, "ffm", failures)) {
                    return;
                }
            }
            skipped = failures.toString();
        }
        factory = new HeapPhysicsFactory();
        LOGGER.info("Celeris physics engine: {} ({})", factory.engineName(), skipped);
    }

    /** Instantiates one native factory; on success it becomes the engine, on failure the reason is added to {@code failures}. */
    private static boolean load(String className, String label, StringBuilder failures) {
        try {
            factory = (PhysicsFactory) Class.forName(className).getDeclaredConstructor().newInstance();
            LOGGER.info("Celeris physics engine: {}{}", factory.engineName(),
                    failures.length() > 0 ? " (skipped: " + failures + ")" : "");
            return true;
        } catch (Throwable t) {
            // The constructor runs reflectively: report what it threw, not the wrapper.
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            if (cause instanceof ExceptionInInitializerError && cause.getCause() != null) {
                cause = cause.getCause();
            }
            append(failures, label + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + (cause instanceof IllegalCallerException && Runtime.version().feature() == 21
                            ? " (on Java 21 --enable-native-access blocks FFM calls from mods: remove it, or use JNI)" : ""));
            return false;
        }
    }

    private static void append(StringBuilder failures, String reason) {
        if (failures.length() > 0) {
            failures.append("; ");
        }
        failures.append(reason);
    }
}
