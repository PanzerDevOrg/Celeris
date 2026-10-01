package com.panzer.mods.celeris_example.compressor;

import com.panzer.mods.celeris_example.compressor.registry.*;
import net.neoforged.bus.api.IEventBus;

public final class CompressorSetup {
    private CompressorSetup() {}

    public static void register(IEventBus modBus) {
        CompressorBlocks.BLOCKS.register(modBus);
        CompressorItems.ITEMS.register(modBus);
        CompressorBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
        CompressorMenus.MENU_TYPES.register(modBus);

        modBus.addListener(CompressorCapabilities::capabilities);
    }
}
