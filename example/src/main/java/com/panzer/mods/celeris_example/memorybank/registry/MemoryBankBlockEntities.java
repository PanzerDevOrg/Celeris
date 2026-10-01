package com.panzer.mods.celeris_example.memorybank.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.memorybank.MemoryBankBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MemoryBankBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CelerisExample.MOD_ID);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MemoryBankBlockEntity>> MEMORY_BANK =
            BLOCK_ENTITY_TYPES.register("memory_bank", () ->
                    BlockEntityType.Builder.of(
                            MemoryBankBlockEntity::new,
                            MemoryBankBlocks.MEMORY_BANK.get()
                    ).build(null)
            );
}
