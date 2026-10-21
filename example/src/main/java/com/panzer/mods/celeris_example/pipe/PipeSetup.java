package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris_example.pipe.net.PipeNetwork;
import com.panzer.mods.celeris_example.pipe.registry.*;
import net.neoforged.bus.api.IEventBus;

/**
 * Registers the pipe block/item and its network channel. {@link PipeEvents}
 * wires itself up via {@code @EventBusSubscriber} and needs no call here.
 */
public final class PipeSetup {
    private PipeSetup() {
    }

    public static void register(IEventBus modBus) {
        PipeBlocks.BLOCKS.register(modBus);
        PipeItems.ITEMS.register(modBus);
        modBus.addListener(PipeNetwork::register);
    }
}
