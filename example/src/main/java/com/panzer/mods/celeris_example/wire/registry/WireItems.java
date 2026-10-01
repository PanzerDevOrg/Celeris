package com.panzer.mods.celeris_example.wire.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WireItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CelerisExample.MOD_ID);

    public static final DeferredItem<BlockItem> WIRE =
            ITEMS.registerSimpleBlockItem("wire", WireBlocks.WIRE);
}
