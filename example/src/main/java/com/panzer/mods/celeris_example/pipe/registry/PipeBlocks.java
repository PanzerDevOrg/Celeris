package com.panzer.mods.celeris_example.pipe.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.pipe.PipeBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PipeBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<PipeBlock> PIPE =
            BLOCKS.register("pipe", () -> new PipeBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(1.0f)
                            .explosionResistance(3.0f)
            ));
}
