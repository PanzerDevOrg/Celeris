package com.panzer.mods.celeris_example.compressor.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.compressor.block.CompressorBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CompressorBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CelerisExample.MOD_ID);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CompressorBlockEntity>> COMPRESSOR =
            BLOCK_ENTITY_TYPES.register("compressor", () ->
                    BlockEntityType.Builder.of(
                            CompressorBlockEntity::new,
                            CompressorBlocks.COMPRESSOR.get()
                    ).build(null)
            );
}
