package com.panzer.mods.celeris;

import com.panzer.mods.celeris.core.memory.CelerisCodecs;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.network.NetworkRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point. Triggers backend/codec selection (see {@link
 * CelerisRuntime}, {@link CelerisCodecs}) and network registration on
 * startup; has no state or methods another mod would call directly -- for
 * runtime feature checks, use {@code com.panzer.mods.celeris.api.CelerisFeatures}
 * instead.
 */
@Mod(Celeris.MOD_ID)
public final class Celeris {

    public static final String MOD_ID = "celeris";
    private static final Logger LOGGER = LoggerFactory.getLogger(Celeris.class);

    @SuppressWarnings("resource")
    public Celeris(IEventBus modEventBus, ModContainer ignoredModContainer) {
        LOGGER.info("Celeris engine initializing");

        LOGGER.info("Celeris memory backend: {}", CelerisRuntime.backend().name());
        LOGGER.info("Celeris compression codec: {}", CelerisCodecs.compressionCodec().name());

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(NetworkRegistry::register);

        LOGGER.info("Celeris engine working");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Celeris common setup complete");
    }
}
