package com.panzer.mods.celeris_example.compressor.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.compressor.block.CompressorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CompressorBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<CompressorBlock> COMPRESSOR =
            BLOCKS.register("compressor", () -> new CompressorBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(3.5f)
                            .explosionResistance(6.0f)
            ));
}
