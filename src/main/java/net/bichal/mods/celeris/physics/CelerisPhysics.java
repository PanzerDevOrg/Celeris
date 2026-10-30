package net.bichal.mods.celeris.physics;

import net.bichal.mods.celeris.core.memory.backend.CelerisRuntime;
import java.lang.reflect.InvocationTargetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the batch physics engine. Selected once per JVM, like
 * {@link CelerisRuntime} and the SIMD runtime, and always available unless
 * turned off -- no JVM flags are required.
 *
 * <p>The default is the pure-Java kernel. The native kernel is opt-in, because
 * it is faster in the kernel itself (1.5x to 2x) but only a mod whose own work
 * leaves room for that gains anything from it. A mod that wants it calls
 * {@link #allowNative(String)} from its initialiser, before the first batch is
 * created; the player or developer keeps the last word with
 * {@code -Dceleris.physics.engine}. The engines, in the order tried once native
 * is allowed:
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
 *       the default, and what runs whenever the native kernel cannot (no
 *       library for this platform, compat mode).</li>
 * </ol>
 *
 * <p>{@code -Dceleris.physics.engine=}: {@code java} forces the Java kernel even
 * if a mod asked for native, {@code auto} allows native for every mod, {@code
 * jni} or {@code ffm} allow only that binding (falling back to Java if it cannot
 * load), {@code off} disables the engine ({@link #isAvailable()} false: callers
 * keep vanilla code). Unset: native only if a mod called {@link
 * #allowNative(String)}. {@code -Dceleris.physics.isa=generic|avx2|avx512|neon}
 * pins the native ISA.
 */
public final class CelerisPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Physics");
    private static final String JNI_FACTORY_CLASS = "net.bichal.mods.celeris.physics.JniPhysicsFactory";
    private static final String FFM_FACTORY_CLASS = "net.bichal.mods.celeris.physics.SegmentPhysicsFactory";

    private static volatile PhysicsFactory factory;
    private static volatile String unavailableReason;
    private static volatile ChunkWorkers workers;
    /** Mods that asked for the native kernel, in the order they asked. */
    private static final java.util.Set<String> NATIVE_REQUESTS = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private CelerisPhysics() {
    }

    /**
     * Asks for the native kernel on behalf of {@code modId}. Call it from the
     * mod's initialiser, before the first {@link #newBatch} / {@link #isAvailable()}
     * / {@link #engineName()}: the engine is chosen once per JVM, and a request
     * that comes after is logged and has no effect. It is only a request: with
     * {@code -Dceleris.physics.engine=java} the Java kernel runs anyway, and if the
     * native library cannot load, the Java kernel takes over.
     */
    public static void allowNative(String modId) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId");
        }
        NATIVE_REQUESTS.add(modId);
        PhysicsFactory chosen = factory;
        if (chosen != null && !chosen.engineName().startsWith("native")
                && !"java".equalsIgnoreCase(System.getProperty("celeris.physics.engine"))) {
            LOGGER.warn("Celeris: {} asked for the native physics kernel after the engine was chosen ({}); "
                    + "call CelerisPhysics.allowNative before the first batch is created", modId, chosen.engineName());
        }
    }

    /**
     * Which engine family to try for a value of {@code -Dceleris.physics.engine}
     * (null when unset) and whether any mod asked for native: {@code off},
     * {@code java}, {@code jni}, {@code ffm} or {@code auto} (JNI, then FFM, then
     * Java). Unknown values count as unset.
     */
    static String policy(String property, boolean nativeRequested) {
        if (property != null) {
            String p = property.toLowerCase(java.util.Locale.ROOT);
            if (java.util.List.of("auto", "off", "java", "jni", "ffm").contains(p)) {
                return p;
            }
        }
        return nativeRequested ? "auto" : "java";
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
        return newBatch(capacity, mode, (int) Math.min((long) capacity * 4, 1 << 28));
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
        String property = System.getProperty("celeris.physics.engine");
        if (property != null && !java.util.List.of("auto", "off", "java", "jni", "ffm").contains(property.toLowerCase(java.util.Locale.ROOT))) {
            LOGGER.warn("Celeris: unknown -Dceleris.physics.engine={} (expected auto, java, jni, ffm or off); ignoring it", property);
        }
        java.util.List<String> requesters;
        synchronized (NATIVE_REQUESTS) {
            requesters = new java.util.ArrayList<>(NATIVE_REQUESTS);
        }
        String engine = policy(property, !requesters.isEmpty());
        if ("off".equals(engine)) {
            unavailableReason = "disabled by -Dceleris.physics.engine=off";
            LOGGER.info("Celeris physics engine disabled (-Dceleris.physics.engine=off)");
            return;
        }
        String skipped = null;
        if ("java".equals(engine)) {
            skipped = property != null && "java".equalsIgnoreCase(property)
                    ? "-Dceleris.physics.engine=java"
                    : "the native kernel is opt-in: a mod enables it with CelerisPhysics.allowNative, or "
                            + "-Dceleris.physics.engine=auto";
        } else if (CelerisRuntime.isCompatModeForced()) {
            skipped = "compat mode";
        } else {
            StringBuilder failures = new StringBuilder();
            if (!"ffm".equals(engine) && load(JNI_FACTORY_CLASS, "jni", failures)) {
                return;
            }
            if (!"jni".equals(engine)) {
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
        LOGGER.info("Celeris physics engine: {} ({}){}", factory.engineName(), skipped,
                requesters.isEmpty() ? "" : "; native requested by " + String.join(", ", requesters));
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
