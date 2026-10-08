//? if fabric {
/*package com.panzer.mods.celeris.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/^*
 * Fabric's config file, {@code config/celeris.properties}: written with the
 * defaults on first start and read on each start. The Mod Menu screen
 * ({@code CelerisConfigScreen}) changes it through {@link #setCompressionLevel},
 * which applies at once and saves. Feeds {@link CelerisSettings}.
 ^/
public final class CelerisFabricConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CelerisFabricConfig.class);
    private static final String COMPRESSION_LEVEL = "compressionLevel";

    private static volatile int compressionLevel = CelerisSettings.DEFAULT_COMPRESSION_LEVEL;

    private CelerisFabricConfig() {}

    public static void load() {
        Path file = file();
        int level = CelerisSettings.DEFAULT_COMPRESSION_LEVEL;
        if (Files.isRegularFile(file)) {
            Properties props = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
                level = parseLevel(props.getProperty(COMPRESSION_LEVEL), file);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}; using the defaults", file, e);
            }
        } else {
            save(file, level);
        }
        compressionLevel = level;
        CelerisSettings.compressionLevelSource(() -> compressionLevel);
    }

    /^* Applies {@code level} (clamped) from the next packet on and writes it to the file. ^/
    public static void setCompressionLevel(int level) {
        int clamped = CelerisSettings.clampCompressionLevel(level);
        if (clamped != compressionLevel) {
            compressionLevel = clamped;
            save(file(), clamped);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("celeris.properties");
    }

    private static int parseLevel(String value, Path file) {
        if (value == null) {
            return CelerisSettings.DEFAULT_COMPRESSION_LEVEL;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            int clamped = CelerisSettings.clampCompressionLevel(parsed);
            if (clamped != parsed) {
                LOGGER.warn("{}: {}={} is outside {}-{}; using {}", file, COMPRESSION_LEVEL, parsed,
                        CelerisSettings.MIN_COMPRESSION_LEVEL, CelerisSettings.MAX_COMPRESSION_LEVEL, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            LOGGER.warn("{}: {}={} is not a number; using {}", file, COMPRESSION_LEVEL, value,
                    CelerisSettings.DEFAULT_COMPRESSION_LEVEL);
            return CelerisSettings.DEFAULT_COMPRESSION_LEVEL;
        }
    }

    private static void save(Path file, int level) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# Celeris settings. Read at startup: restart after editing this file by hand\n");
                out.write("# (changes made from the Mod Menu screen apply at once).\n");
                out.write("#\n");
                out.write("# Compression level for Celeris network payloads (deflate, the network format).\n");
                out.write("# 0 sends them uncompressed (least CPU, most bandwidth), 1 is the fastest,\n");
                out.write("# 9 the smallest packets. Only the sender's level matters.\n");
                out.write(COMPRESSION_LEVEL + "=" + level + "\n");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
*///?}
