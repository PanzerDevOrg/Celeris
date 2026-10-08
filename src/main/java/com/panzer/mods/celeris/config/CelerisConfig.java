//? if neoforge {
package com.panzer.mods.celeris.config;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config file ({@code config/celeris-common.toml}) and the values the
 * in-game screen (Mods &gt; Celeris &gt; Config) edits. Feeds {@link CelerisSettings}.
 */
public final class CelerisConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        COMPRESSION_LEVEL = b.comment("Compression level for Celeris network payloads (deflate, the network format).",
                        "0 sends them uncompressed (least CPU, most bandwidth), 1 is the fastest, 9 the smallest packets.",
                        "Only the sender's level matters; a change applies to the next packet.")
                .translation("celeris.configuration.compressionLevel")
                .defineInRange("compressionLevel", CelerisSettings.DEFAULT_COMPRESSION_LEVEL,
                        CelerisSettings.MIN_COMPRESSION_LEVEL, CelerisSettings.MAX_COMPRESSION_LEVEL);
        SPEC = b.build();
    }

    private CelerisConfig() {}

    /** Points {@link CelerisSettings} at this file; the default applies until the file is loaded. */
    public static void install() {
        CelerisSettings.compressionLevelSource(() -> SPEC.isLoaded()
                ? COMPRESSION_LEVEL.getAsInt()
                : CelerisSettings.DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * COMMON (loaded on each side, not synced); from NeoForge 26.3 the types are
     * CLIENT, SYNCED and LOCAL, where LOCAL is what COMMON was. Looked up by name
     * so one jar runs on both.
     */
    public static ModConfig.Type type() {
        for (ModConfig.Type type : ModConfig.Type.values()) {
            if (type.name().equals("COMMON")) {
                return type;
            }
        }
        return ModConfig.Type.valueOf("LOCAL");
    }
}
//?}
