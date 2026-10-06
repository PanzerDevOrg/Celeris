package com.panzer.mods.celeris_example.wire;

import com.panzer.mods.celeris_example.wire.registry.*;
import net.neoforged.bus.api.IEventBus;

/**
 * Registers the wire block/item. {@link WireEvents} wires itself up via
 * {@code @EventBusSubscriber} and needs no call here -- only the
 * {@link net.neoforged.neoforge.registries.DeferredRegister}s need the mod
 * bus.
 */
public final class WireSetup {
    private WireSetup() {
    }

    public static void register(IEventBus modBus) {
        WireBlocks.BLOCKS.register(modBus);
        WireItems.ITEMS.register(modBus);
    }
}
