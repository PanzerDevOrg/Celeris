package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Resolves the {@link CompressionCodec} to use, once per JVM, mirroring
 * {@code CelerisRuntime}'s backend selection:
 *
 * <ol>
 *   <li>Compat mode forced, or a backend without raw addresses (heap):
 *       deflate.</li>
 *   <li>Otherwise native zstd through the first binding that loads and
 *       passes a round-trip self-test: FFM when the FFM backend is active
 *       (Java 25, or 21 with {@code --enable-preview}), then JNI, which needs
 *       no launch flag and so gives Java 21 players native zstd by default
 *       (on the Unsafe backend).</li>
 *   <li>No binding works (no libzstd for this platform, or it fails to load):
 *       deflate, with the reasons logged once.</li>
 * </ol>
 *
 * <p>Network payloads always use {@link #wireCodec()} instead, see there.
 *
 * <p>External consumers should not call this class directly -- use
 * {@code CelerisFeatures#isNativeCompressionActive()} instead, which is the
 * supported public entry point for this same information.
 */
@SuppressWarnings("JavadocReference")
public final class CelerisCodecs {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Runtime");

    /** In the {@code ffm} source set: named only as a string so this class never links against it. */
    private static final String FFM_BINDING_CLASS = "com.panzer.mods.celeris.core.memory.ZstdFfmBinding";
    private static final int SELF_TEST_BYTES = 4096;

    private static volatile CompressionCodec codec;

    private CelerisCodecs() {
    }

    public static CompressionCodec compressionCodec() {
        CompressionCodec existing = codec;
        if (existing != null) {
            return existing;
        }
        synchronized (CelerisCodecs.class) {
            if (codec == null) {
                codec = selectCodec();
            }
            return codec;
        }
    }

    /**
     * Codec for anything that crosses the network. Always deflate: the local
     * codec depends on each JVM's flags, version and platform, and payloads
     * carry no codec id, so a zstd server and a deflate client could not read
     * each other. JDK Deflater is native zlib, so this is not a pure-Java path.
     */
    public static CompressionCodec wireCodec() {
        return WireCodecHolder.INSTANCE;
    }

    private static final class WireCodecHolder {
        static final CompressionCodec INSTANCE = new DeflateCompressionCodec();
    }

    /**
     * Whether the active {@link CompressionCodec} is native zstd, as opposed
     * to the deflate fallback. Triggers codec selection on first call, exactly
     * like {@link #compressionCodec()}. Backs the public
     * {@code CelerisFeatures.isNativeCompressionActive()}.
     */
    public static boolean isNativeActive() {
        return compressionCodec() instanceof ZstdCompressionCodec;
    }

    @SuppressWarnings("resource")
    private static CompressionCodec selectCodec() {
        if (CelerisRuntime.isCompatModeForced()) {
            LOGGER.info("Celeris compression codec: deflate (compat mode forced)");
            return new DeflateCompressionCodec();
        }
        MemoryBackend backend = CelerisRuntime.backend();
        if (!backend.supportsRawAddress()) {
            LOGGER.info("Celeris compression codec: deflate ({} backend has no native addresses)", backend.name());
            return new DeflateCompressionCodec();
        }

        StringBuilder failures = new StringBuilder();
        if (CelerisRuntime.isFfmActive()) {
            CompressionCodec ffm = tryBinding("FFM", () -> (ZstdBinding) Class.forName(FFM_BINDING_CLASS)
                    .getDeclaredConstructor().newInstance(), backend, failures);
            if (ffm != null) {
                return ffm;
            }
        }
        CompressionCodec jni = tryBinding("JNI", () -> ZstdJniBinding.INSTANCE, backend, failures);
        if (jni != null) {
            return jni;
        }
        LOGGER.warn("Celeris native zstd unavailable ({}) -- compression codec: deflate", failures);
        return new DeflateCompressionCodec();
    }

    @FunctionalInterface
    private interface BindingLoader {
        ZstdBinding load() throws Exception;
    }

    private static CompressionCodec tryBinding(String label, BindingLoader loader, MemoryBackend backend,
                                               StringBuilder failures) {
        try {
            ZstdCompressionCodec candidate = new ZstdCompressionCodec(loader.load());
            selfTest(candidate, backend);
            LOGGER.info("Celeris compression codec: zstd {} (native, {})", candidate.nativeVersion(), candidate.bindingName());
            return candidate;
        } catch (Throwable t) {
            // Missing library, missing symbol, wrong ABI, or a JVM without FFM:
            // each surfaces as a different Throwable, so none is singled out.
            if (failures.length() > 0) {
                failures.append("; ");
            }
            Throwable cause = t;
            while ((cause instanceof ExceptionInInitializerError || cause instanceof InvocationTargetException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            failures.append(label).append(": ").append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage());
            if (cause instanceof IllegalCallerException && Runtime.version().feature() == 21) {
                failures.append(" (on Java 21 --enable-native-access blocks native calls from mods: remove it)");
            }
            return null;
        }
    }

    /** One real round trip before committing to a binding, so a broken library degrades to deflate instead of failing later. */
    private static void selfTest(CompressionCodec candidate, MemoryBackend backend) {
        byte[] sample = new byte[SELF_TEST_BYTES];
        for (int i = 0; i < sample.length; i++) {
            sample[i] = (byte) (i * 7 % 61);
        }
        long bound = candidate.compressBound(sample.length);
        long src = backend.allocate(sample.length, 8L);
        long packed = backend.allocate(bound, 8L);
        long out = backend.allocate(sample.length, 8L);
        try {
            backend.copyFromHeap(src, 0L, sample, 0, sample.length);
            long size = candidate.compress(backend, packed, bound, src, 0L, sample.length, 3);
            long restored = candidate.decompress(backend, out, sample.length, packed, 0L, size);
            byte[] check = new byte[sample.length];
            backend.copyToHeap(out, 0L, check, 0, check.length);
            if (restored != sample.length || !java.util.Arrays.equals(sample, check)) {
                throw new IllegalStateException("self-test round trip mismatch");
            }
        } finally {
            backend.free(src);
            backend.free(packed);
            backend.free(out);
        }
    }
}
