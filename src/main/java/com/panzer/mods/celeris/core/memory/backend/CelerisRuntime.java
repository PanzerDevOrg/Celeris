package com.panzer.mods.celeris.core.memory.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides once, for the whole JVM lifetime, which {@link MemoryBackend}
 * Celeris runs on:
 *
 * <ol>
 *   <li>If {@code -Dceleris.compatMode=true} (or the equivalent config
 *       entry, wired by the block layer at startup via
 *       {@link #forceCompatMode()}) is set, always use {@link HeapMemoryBackend}
 *       -- no detection attempted, no FFM class ever touched.</li>
 *   <li>Otherwise, probe whether FFM is actually usable on this JVM by
 *       performing one real allocation through it. A JVM missing
 *       {@code --enable-preview} (JDK 21/22) or missing the module
 *       entirely raises a variety of errors depending on version and
 *       classloading state -- {@code NoClassDefFoundError},
 *       {@code UnsupportedClassVersionError}, {@code IllegalAccessError},
 *       or a plain {@code ExceptionInInitializerError} -- so the probe
 *       catches {@code Throwable} deliberately rather than guessing which
 *       one applies for a given JDK build.</li>
 *   <li>If the probe throws anything, fall back to {@link HeapMemoryBackend}
 *       and log the concrete reason once, so a support report shows exactly
 *       why compat mode engaged instead of just "it's slow".</li>
 * </ol>
 *
 * <p>The result is cached in a {@code volatile} field after first use; every
 * caller after the first gets the same backend instance without repeating
 * the probe. This class holds no reference to any FFM type in its own
 * fields or method signatures, so it loads cleanly even on a JVM where FFM
 * classes don't exist at all -- only {@link #probeFfm()} ever touches them,
 * behind the try/catch.
 *
 * <p>External consumers should not call this class directly -- use
 * {@code com.panzer.mods.celeris.api.CelerisFeatures#isFfmActive()} instead,
 * which is the supported public entry point for this same information.
 */
public final class CelerisRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Runtime");
    private static final String COMPAT_MODE_PROPERTY = "celeris.compatMode";
    private static final String FFM_BACKEND_CLASS = "com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend";
    private static final String UNSAFE_BACKEND_CLASS = "com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend";
    /**
     * Off-heap backends in preference order: FFM (Java 22+, or 21 with
     * --enable-preview), then Unsafe (any JDK, no flags). Heap is the final
     * pure-Java fallback when neither loads.
     */
    private static final String[] OFF_HEAP_BACKEND_CLASSES = {FFM_BACKEND_CLASS, UNSAFE_BACKEND_CLASS};

    private static volatile MemoryBackend backend;
    private static volatile boolean forcedCompatMode;

    private CelerisRuntime() {
    }

    /**
     * Forces compat mode regardless of what the FFM probe would find.
     * Must be called before the first {@link #backend()} call to have any
     * effect -- intended to be invoked from mod config loading, ahead of
     * any subsystem that touches memory (i.e. before {@code FMLCommonSetupEvent}).
     * <p>
     * Also governs {@code VectorOperations}' SIMD-vs-scalar selection (see
     * {@code com.panzer.mods.celeris.api.simd.CelerisVectorRuntime}),
     * which reads this same flag -- one setting forces the whole engine
     * into its pure-Java paths, not just memory.
     */
    public static void forceCompatMode() {
        forcedCompatMode = true;
    }

    /** Whether compat mode was forced via {@link #forceCompatMode()} or the system property. */
    public static boolean isCompatModeForced() {
        return forcedCompatMode || Boolean.getBoolean(COMPAT_MODE_PROPERTY);
    }

    /**
     * Whether the active {@link MemoryBackend} is the real FFM one, as
     * opposed to the {@link HeapMemoryBackend} compat fallback. Triggers
     * backend selection on first call, exactly like {@link #backend()}.
     *
     * <p>This is the method backing the public
     * {@code CelerisFeatures.isFfmActive()} -- prefer that entry point from
     * outside this package; this one stays here so the backend package
     * remains the single source of truth for the check.
     */
    @SuppressWarnings("resource")
    public static boolean isFfmActive() {
        return FFM_BACKEND_CLASS.equals(backend().getClass().getName());
    }

    /** Whether any off-heap backend (FFM or Unsafe) is active, as opposed to the heap fallback. */
    public static boolean isOffHeapActive() {
        return !(backend() instanceof HeapMemoryBackend);
    }

    public static MemoryBackend backend() {
        MemoryBackend existing = backend;
        if (existing != null) {
            return existing;
        }
        synchronized (CelerisRuntime.class) {
            if (backend == null) {
                backend = selectBackend();
            }
            return backend;
        }
    }

    private static MemoryBackend selectBackend() {
        if (forcedCompatMode || Boolean.getBoolean(COMPAT_MODE_PROPERTY)) {
            LOGGER.info("Celeris compat mode forced -- using {} backend", "Heap");
            return new HeapMemoryBackend();
        }

        StringBuilder failures = new StringBuilder();
        for (String className : OFF_HEAP_BACKEND_CLASSES) {
            Object probeResult = probe(className);
            if (probeResult instanceof MemoryBackend chosen) {
                if (failures.length() > 0) {
                    LOGGER.info("Celeris {} backend active (skipped: {})", chosen.name(), failures);
                } else {
                    LOGGER.info("Celeris {} backend active -- running at full performance", chosen.name());
                }
                return chosen;
            }
            Throwable t = (Throwable) probeResult;
            if (failures.length() > 0) {
                failures.append("; ");
            }
            failures.append(className.substring(className.lastIndexOf('.') + 1))
                    .append(": ").append(t.getClass().getSimpleName()).append(' ').append(t.getMessage());
        }
        LOGGER.warn("Celeris off-heap backends unavailable ({}) -- falling back to pure-Java heap mode.", failures);
        return new HeapMemoryBackend();
    }

    /**
     * Instantiates the FFM backend and performs one real allocate / write /
     * read / free through it, entirely inside this method so no other code
     * in this file references {@code java.lang.foreign} at the bytecode
     * level outside a try/catch. On success the <em>same</em> instance is
     * returned and becomes the JVM-wide backend (no second instantiation);
     * on failure the caught {@link Throwable} is returned instead.
     */
    private static Object probe(String className) {
        MemoryBackend candidate = null;
        try {
            Class<?> ffmClass = Class.forName(className);
            candidate = (MemoryBackend) ffmClass.getDeclaredConstructor().newInstance();
            long h = candidate.allocate(Long.BYTES, Long.BYTES);
            candidate.setLongRelease(h, 0, 0x5EEDL);
            long back = candidate.getLongAcquire(h, 0);
            candidate.free(h);
            if (back != 0x5EEDL) {
                throw new IllegalStateException("Probe round-trip mismatch: " + back);
            }
            return candidate;
        } catch (Throwable t) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
            return t;
        }
    }
}
