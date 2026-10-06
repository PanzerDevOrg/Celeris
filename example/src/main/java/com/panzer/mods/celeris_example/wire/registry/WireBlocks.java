package com.panzer.mods.celeris_example.wire.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.wire.WireBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WireBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<WireBlock> WIRE =
            BLOCKS.register("wire", () -> new WireBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(0.2f)
                            .explosionResistance(0.2f)
            ));
}
