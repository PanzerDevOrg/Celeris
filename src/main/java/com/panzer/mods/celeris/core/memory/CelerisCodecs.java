package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@link CompressionCodec} to use, mirroring the same
 * once-per-JVM, cache-after-first-call pattern {@code CelerisRuntime} uses
 * for {@code MemoryBackend} selection. Kept separate from {@code
 * CelerisRuntime} itself (which lives in the {@code backend} package)
 * because the backend layer has no business knowing about compression --
 * {@code ZstdCompressionCodec}/{@code ZstdCodec} live in this package
 * specifically so {@code backend} never has a reason to reference them.
 *
 * <p>The choice is entirely derived from {@link CelerisRuntime#backend()}'s
 * result -- there is no independent detection here. If the backend is
 * {@link com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend}, zstd is used (native, fast); for anything else
 * (currently only {@code HeapMemoryBackend}), Deflate is used (pure Java,
 * slower, no native/preview dependency). This keeps exactly one source of
 * truth for "is FFM available" instead of two independent probes that could
 * disagree.
 *
 * <p>External consumers should not call this class directly -- use
 * {@code CelerisFeatures#isNativeCompressionActive()}
 * instead, which is the supported public entry point for this same
 * information.
 */
@SuppressWarnings("JavadocReference")
public final class CelerisCodecs {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Runtime");

    private static final String FFM_MEMORY_PATH = "com.panzer.mods.celeris.core.memory.";
    private static final String FFM_BACKEND_CLASS = FFM_MEMORY_PATH + "backend.FfmMemoryBackend";
    private static final String ZSTD_CODEC_CLASS = FFM_MEMORY_PATH + "ZstdCompressionCodec";

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
     * Whether the active {@link CompressionCodec} is native zstd, as
     * opposed to the pure-Java deflate fallback. Triggers codec selection
     * on first call, exactly like {@link #compressionCodec()}.
     *
     * <p>This is the method backing the public
     * {@code CelerisFeatures.isNativeCompressionActive()} -- prefer that
     * entry point from outside this package.
     */
    /**
     * Codec for anything that crosses the network. Always deflate: the local
     * codec depends on each JVM's flags/version (zstd needs FFM), and payloads
     * carry no codec id, so a zstd server and a deflate client could not read
     * each other. JDK Deflater is native zlib, so this is not a pure-Java path.
     */
    public static CompressionCodec wireCodec() {
        return WireCodecHolder.INSTANCE;
    }

    private static final class WireCodecHolder {
        static final CompressionCodec INSTANCE = new DeflateCompressionCodec();
    }

    public static boolean isNativeActive() {
        return ZSTD_CODEC_CLASS.equals(compressionCodec().getClass().getName());
    }

    @SuppressWarnings("resource")
    private static CompressionCodec selectCodec() {
        if (FFM_BACKEND_CLASS.equals(CelerisRuntime.backend().getClass().getName())) {
            try {
                LOGGER.info("Celeris compression codec: zstd (native)");
                return (CompressionCodec) Class.forName(ZSTD_CODEC_CLASS)
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Zstd codec unavailable despite FFM backend -- falling back to deflate", e);
            }
        }
        LOGGER.info("Celeris compression codec: deflate (pure Java compat mode)");
        return new DeflateCompressionCodec();
    }
}
