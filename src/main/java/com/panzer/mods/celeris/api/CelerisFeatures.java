package com.panzer.mods.celeris.api;

import com.panzer.mods.celeris.core.memory.CelerisCodecs;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.api.simd.VectorOperations;

/**
 * One-stop check for what Celeris is actually running on <em>right now</em>
 * on this JVM: off-heap memory (FFM vs heap), SIMD math (vector vs scalar),
 * and native compression (zstd vs deflate).
 *
 * <p>You never need Celeris's optional {@code ffm}/{@code vector} source
 * modules to use this class -- it lives in {@code main} and always
 * compiles, on every Minecraft version Celeris supports. If you only need
 * a "should I take the fast path?" answer, this is the class to call:
 *
 * <pre>{@code
 * if (CelerisFeatures.isFfmActive()) {
 *     // native memory / zstd available -- use the fast path
 * } else {
 *     // running in compat mode -- heap-backed, still correct, just slower
 * }
 * }</pre>
 *
 * <p>Every method here is cheap after the first call: Celeris decides its
 * backends once per JVM lifetime and caches the result, so these methods
 * just read that cached decision.
 *
 * @since 0.1.0
 */
public final class CelerisFeatures {

    private CelerisFeatures() {
    }

    /**
     * Whether off-heap memory is backed by real FFM ({@code MemorySegment})
     * rather than the {@code ByteBuffer}-based compat fallback.
     *
     * <p>False on a JVM launched without {@code --enable-preview} (or with
     * {@code -Dceleris.compatMode=true}) -- Celeris still works either way,
     * this just tells you which path you're on.
     */
    public static boolean isFfmActive() {
        return CelerisRuntime.isFfmActive();
    }

    /**
     * Whether SIMD math ({@code jdk.incubator.vector}) is active for
     * {@link VectorOperations}, as opposed to the pure-Java scalar fallback.
     *
     * <p>False on a JVM launched without
     * {@code --add-modules=jdk.incubator.vector} -- results are identical
     * either way, only throughput differs.
     */
    public static boolean isSimdActive() {
        return VectorOperations.isSimdActive();
    }

    /**
     * Whether network payload compression is using native zstd rather than
     * the pure-Java deflate fallback.
     *
     * <p>This always mirrors {@link #isFfmActive()} -- native compression
     * needs the FFM backend's raw pointers -- but is exposed separately so
     * you don't have to remember that relationship.
     */
    public static boolean isNativeCompressionActive() {
        return CelerisCodecs.isNativeActive();
    }

    /** Short backend name for logs/diagnostics, e.g. {@code "FFM"} or {@code "Heap (compat mode)"}. */
    @SuppressWarnings("resource")
    public static String memoryBackendName() {
        return CelerisRuntime.backend().name();
    }

    /** Short backend name for logs/diagnostics, e.g. {@code "SIMD (256-bit)"} or {@code "scalar (compat mode)"}. */
    public static String vectorBackendName() {
        return VectorOperations.backendName();
    }

    /** Short codec name for logs/diagnostics, e.g. {@code "zstd"} or {@code "deflate"}. */
    public static String compressionCodecName() {
        return CelerisCodecs.compressionCodec().name();
    }

    /**
     * True only if every fast path is active. Handy as a single flag for a
     * mod's debug screen or {@code /modinfo}-style command; for anything
     * more granular, call the individual methods above instead.
     */
    public static boolean isRunningAtFullPerformance() {
        return isFfmActive() && isSimdActive() && isNativeCompressionActive();
    }
}
