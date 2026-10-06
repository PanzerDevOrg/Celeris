package com.panzer.mods.celeris_example.compressor.registry;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public class CompressorCapabilities {

    public static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                CompressorBlockEntities.COMPRESSOR.get(),
                (blockEntity, ignoredSide) -> blockEntity.getItemHandler()
        );
    }
}
