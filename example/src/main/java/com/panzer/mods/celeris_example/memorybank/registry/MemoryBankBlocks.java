package com.panzer.mods.celeris_example.memorybank.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.memorybank.MemoryBankBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MemoryBankBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<MemoryBankBlock> MEMORY_BANK =
            BLOCKS.register("memory_bank", () -> new MemoryBankBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(2.0f)
                            .explosionResistance(6.0f)
            ));
}
