package com.panzer.mods.celeris_example.ringbuffer.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.ringbuffer.RingBufferDemoBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RingBufferDemoBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CelerisExample.MOD_ID);

    public static final DeferredBlock<RingBufferDemoBlock> RING_BUFFER_DEMO =
            BLOCKS.register("ring_buffer_demo", () -> new RingBufferDemoBlock(
                    BlockBehaviour.Properties.of()
                            .destroyTime(1.0f)
                            .explosionResistance(3.0f)
            ));
}
