package com.panzer.mods.celeris_example.pipe.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PipeItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CelerisExample.MOD_ID);

    public static final DeferredItem<BlockItem> PIPE =
            ITEMS.registerSimpleBlockItem("pipe", PipeBlocks.PIPE);
}
