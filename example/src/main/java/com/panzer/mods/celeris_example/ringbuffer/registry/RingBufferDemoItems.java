package com.panzer.mods.celeris_example.ringbuffer.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RingBufferDemoItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CelerisExample.MOD_ID);

    public static final DeferredItem<BlockItem> RING_BUFFER_DEMO =
            ITEMS.registerSimpleBlockItem("ring_buffer_demo", RingBufferDemoBlocks.RING_BUFFER_DEMO);
}
