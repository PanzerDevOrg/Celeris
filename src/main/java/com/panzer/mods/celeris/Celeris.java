package com.panzer.mods.celeris;

import com.panzer.mods.celeris.core.memory.CelerisCodecs;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if neoforge {
import com.panzer.mods.celeris.network.NetworkRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
//?}

/**
 * Mod entry point on NeoForge (on Fabric: {@code CelerisFabric}). Triggers
 * backend/codec selection (see {@link CelerisRuntime}, {@link CelerisCodecs})
 * and network registration on startup; has no state or methods another mod
 * would call directly -- for runtime feature checks, use
 * {@code com.panzer.mods.celeris.api.CelerisFeatures} instead.
 */
//? if neoforge
@Mod(Celeris.MOD_ID)
public final class Celeris {

    public static final String MOD_ID = "celeris";
    private static final Logger LOGGER = LoggerFactory.getLogger(Celeris.class);

    //? if neoforge {
    public Celeris(IEventBus modEventBus, ModContainer ignoredModContainer) {
        start();
        modEventBus.addListener(NetworkRegistry::register);
    }
    //?}

    /** Selects the memory backend and the compression codec once, at startup, and logs them. Both loaders call it. */
    @SuppressWarnings("resource")
    public static void start() {
        LOGGER.info("Celeris engine initializing");
        LOGGER.info("Celeris memory backend: {}", CelerisRuntime.backend().name());
        LOGGER.info("Celeris compression codec: {}", CelerisCodecs.compressionCodec().name());
        LOGGER.info("Celeris engine working");
    }
}
