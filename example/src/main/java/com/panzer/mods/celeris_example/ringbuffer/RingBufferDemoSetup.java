package com.panzer.mods.celeris_example.ringbuffer;

import com.panzer.mods.celeris_example.ringbuffer.registry.*;
import net.neoforged.bus.api.IEventBus;

/** Registers the ring buffer demo block/item. {@link RingBufferDemoEvents} wires itself up via {@code @EventBusSubscriber}. */
public final class RingBufferDemoSetup {
    private RingBufferDemoSetup() {
    }

    public static void register(IEventBus modBus) {
        RingBufferDemoBlocks.BLOCKS.register(modBus);
        RingBufferDemoItems.ITEMS.register(modBus);
    }
}
