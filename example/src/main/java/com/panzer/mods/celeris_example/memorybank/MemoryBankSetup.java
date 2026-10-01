package com.panzer.mods.celeris_example.memorybank;

import com.panzer.mods.celeris_example.memorybank.registry.*;
import net.neoforged.bus.api.IEventBus;

public final class MemoryBankSetup {
    private MemoryBankSetup() {
    }

    public static void register(IEventBus modBus) {
        MemoryBankBlocks.BLOCKS.register(modBus);
        MemoryBankItems.ITEMS.register(modBus);
        MemoryBankBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
    }
}
