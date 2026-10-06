package com.panzer.mods.celeris_example.payloaddemo;

import com.panzer.mods.celeris_example.payloaddemo.registry.*;
import net.neoforged.bus.api.IEventBus;

public final class PayloadDemoSetup {
    private PayloadDemoSetup() {
    }

    public static void register(IEventBus modBus) {
        PayloadDemoBlocks.BLOCKS.register(modBus);
        PayloadDemoItems.ITEMS.register(modBus);
    }
}
