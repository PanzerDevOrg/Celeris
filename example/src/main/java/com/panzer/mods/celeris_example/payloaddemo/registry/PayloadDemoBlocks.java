package com.panzer.mods.celeris_example.payloaddemo.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.payloaddemo.PayloadDemoBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PayloadDemoBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<PayloadDemoBlock> PAYLOAD_DEMO =
            BLOCKS.register("payload_demo", () -> new PayloadDemoBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(1.0f)
                            .explosionResistance(3.0f)
            ));
}
