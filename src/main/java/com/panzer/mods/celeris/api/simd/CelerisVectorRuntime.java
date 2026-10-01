package com.panzer.mods.celeris.api.simd;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides once, for the whole JVM lifetime, which {@link VectorBackend}
 * {@link VectorOperations} runs on -- mirroring {@link CelerisRuntime}'s
 * memory-backend selection, but kept in this package since the {@code
 * backend} package has no business knowing about {@code
 * jdk.incubator.vector} any more than it does about compression codecs
 * (see {@code CelerisCodecs} for the same reasoning applied there).
 *
 * <p><b>Lives in {@code src/main/java}, NOT {@code src/vector/java}.</b>
 * This class is called directly by {@link VectorOperations}, which is
 * itself in {@code main} -- and the convention plugin ({@code
 * panzer.neoforge-mod.gradle.kts}) intentionally never lets {@code
 * main}'s compile classpath see a module source set's output, only the
 * reverse (each module gets {@code compileOnly} access to {@code main}).
 * Putting the selector in {@code vector} was the bug: it made {@code
 * main} depend on {@code vector}'s output, the one direction the plugin
 * exists to prevent.
 *
 * <p><b>Unlike {@code CelerisRuntime#probeFfm()}, this class cannot name
 * any {@code jdk.incubator.vector} type directly anywhere</b> -- not even
 * inside a try/catch. {@code java.lang.foreign} (used by {@code
 * CelerisRuntime}) is a standard JDK module and always resolves at
 * {@code javac} time; {@code jdk.incubator.vector} is an <em>incubator</em>
 * module and {@code javac} refuses to resolve it at all without {@code
 * --add-modules=jdk.incubator.vector} on the compile task -- a flag
 * {@code main}'s {@code compileJava} must never receive. So both the probe
 * and the real backend are reached exclusively through reflection
 * ({@link Class#forName}), entirely inside {@code vector}-side code
 * ({@link SimdVectorBackend}) that this class never references by static
 * type.
 *
 * <ol>
 *   <li>If {@link CelerisRuntime#isCompatModeForced()} is true (the same
 *       flag that forces the heap memory backend), scalar is used
 *       unconditionally -- one setting puts the whole engine into its
 *       pure-Java paths, not just memory.</li>
 *   <li>Otherwise, reflectively probes {@code SimdVectorBackend}. A JVM
 *       missing {@code --add-modules=jdk.incubator.vector} throws
 *       (typically {@code NoClassDefFoundError}, surfaced here via
 *       {@link ReflectiveOperationException} / {@link Error}) the moment
 *       that class is touched, so both the probe and the eventual real
 *       construction are wrapped defensively here.</li>
 *   <li>Falls back to {@link ScalarVectorBackend} on any probe failure,
 *       logging the concrete cause once.</li>
 * </ol>
 */
@SuppressWarnings("JavadocReference")
final class CelerisVectorRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Runtime");

    // Referenced only by fully-qualified string, exactly like
    // CelerisRuntime.FFM_BACKEND_CLASS -- main never mentions
    // SimdVectorBackend (or anything in jdk.incubator.vector) by static
    // type, so compileJava for main needs zero knowledge that the
    // `vector` source set, or the incubator module it wraps, exist.
    private static final String SIMD_BACKEND_CLASS =
            "com.panzer.mods.celeris.api.simd.SimdVectorBackend";

    private static volatile VectorBackend backend;

    private CelerisVectorRuntime() {
    }

    static VectorBackend backend() {
        VectorBackend existing = backend;
        if (existing != null) {
            return existing;
        }
        synchronized (CelerisVectorRuntime.class) {
            if (backend == null) {
                backend = selectBackend();
            }
            return backend;
        }
    }

    /** Whether the selected backend is the real SIMD one. Triggers selection. */
    static boolean isSimdActive() {
        return SIMD_BACKEND_CLASS.equals(backend().getClass().getName());
    }

    private static VectorBackend selectBackend() {
        if (CelerisRuntime.isCompatModeForced()) {
            LOGGER.info("Celeris vector backend forced -- using scalar (compat mode)");
            return new ScalarVectorBackend();
        }

        Object probeResult = probeVector();
        if (probeResult instanceof VectorBackend simd) {
            LOGGER.info("Celeris vector backend: {}", simd.name());
            return simd;
        }

        Throwable probeFailure = (Throwable) probeResult;
        if (probeFailure instanceof NoClassDefFoundError) {
            // Module not resolved: the normal state for players, since launchers
            // never pass --add-modules. INFO, so it doesn't read as a fault in bug reports.
            LOGGER.info("Celeris vector backend: scalar (jdk.incubator.vector not enabled; "
                    + "launch with --add-modules=jdk.incubator.vector for SIMD)");
        } else {
            // Module present but the probe failed: a genuine problem worth surfacing.
            LOGGER.warn("Celeris SIMD backend failed its self-test ({}: {}) -- falling back to scalar.",
                    probeFailure.getClass().getSimpleName(), probeFailure.getMessage());
        }
        return new ScalarVectorBackend();
    }

    /**
     * Reflectively instantiates {@code SimdVectorBackend} and exercises one
     * real vector operation on it (a tiny {@code addInPlace}), forcing the
     * JVM to link {@code jdk.incubator.vector} classes and surface any
     * {@code NoClassDefFoundError} / {@code IllegalAccessError} here rather
     * than on the first call from {@link VectorOperations}. On success the
     * <em>same</em> instance is returned and used for the JVM lifetime; on
     * failure the underlying {@link Throwable} is returned. This file never
     * names a {@code jdk.incubator.vector} type at the bytecode level.
     */
    private static Object probeVector() {
        try {
            VectorBackend candidate = (VectorBackend) Class.forName(SIMD_BACKEND_CLASS)
                    .getDeclaredConstructor().newInstance();
            if (candidate.preferredLaneCount() < 1) {
                return new IllegalStateException("Vector probe read back an unexpected lane count");
            }
            float[] a = {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f};
            float[] b = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};
            candidate.addInPlace(a, b, a.length);
            if (a[0] != 2f || a[8] != 10f) {
                return new IllegalStateException("Vector probe produced wrong lane results");
            }
            return candidate;
        } catch (Throwable t) {
            return t;
        }
    }
}
