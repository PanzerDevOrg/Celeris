package com.panzer.mods.celeris.config;

import java.util.function.IntSupplier;

/**
 * User settings that loader-independent code reads. Each loader plugs in its
 * own source at startup (NeoForge: {@code CelerisConfig}, a config file with an
 * in-game screen; Fabric: {@code config/celeris.properties}); until then, and in
 * tests, the defaults apply. Values are read on every use, so a change saved
 * from the config screen takes effect on the next packet.
 */
public final class CelerisSettings {

    /** No compression: payloads go out as they are (least CPU, most bandwidth). */
    public static final int MIN_COMPRESSION_LEVEL = 0;
    /** Deflate's maximum: smallest packets, most CPU. */
    public static final int MAX_COMPRESSION_LEVEL = 9;
    /** Deflate's fastest level; what Celeris used before the setting existed. */
    public static final int DEFAULT_COMPRESSION_LEVEL = 1;

    private static final IntSupplier DEFAULTS = () -> DEFAULT_COMPRESSION_LEVEL;
    private static volatile IntSupplier compressionLevel = DEFAULTS;

    private CelerisSettings() {}

    /**
     * Compression level for network payloads on deflate's scale (the network
     * format): 0 sends them uncompressed, 1 is the fastest and 9 the smallest;
     * default {@value #DEFAULT_COMPRESSION_LEVEL}. An out-of-range value from the
     * source is clamped. Only the sender's level matters: the receiver reads any
     * level, so peers need not agree on it.
     */
    public static int compressionLevel() {
        return clampCompressionLevel(compressionLevel.getAsInt());
    }

    /** Sets where {@link #compressionLevel()} comes from; called by the loader entry points. */
    public static void compressionLevelSource(IntSupplier source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        compressionLevel = source;
    }

    /** Restores the default source (for tests). */
    public static void resetToDefaults() {
        compressionLevel = DEFAULTS;
    }

    public static int clampCompressionLevel(int level) {
        return Math.max(MIN_COMPRESSION_LEVEL, Math.min(MAX_COMPRESSION_LEVEL, level));
    }
}
