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
 * defaults on first start, read on each start (Fabric has no shared config
 * screen; edit the file and restart). Feeds {@link CelerisSettings}.
 ^/
public final class CelerisFabricConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CelerisFabricConfig.class);
    private static final String COMPRESSION_LEVEL = "compressionLevel";

    private CelerisFabricConfig() {}

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("celeris.properties");
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
            writeDefaults(file);
        }
        int chosen = level;
        CelerisSettings.compressionLevelSource(() -> chosen);
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

    private static void writeDefaults(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# Celeris settings. Read at startup: restart the game or server after editing.\n");
                out.write("#\n");
                out.write("# Compression level for Celeris network payloads (deflate, the network format).\n");
                out.write("# 0 sends them uncompressed (least CPU, most bandwidth), 1 is the fastest,\n");
                out.write("# 9 the smallest packets. Only the sender's level matters.\n");
                out.write(COMPRESSION_LEVEL + "=" + CelerisSettings.DEFAULT_COMPRESSION_LEVEL + "\n");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
*///?}
