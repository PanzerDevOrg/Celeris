package com.panzer.mods.celeris_example.payloaddemo.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PayloadDemoItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CelerisExample.MOD_ID);

    public static final DeferredItem<BlockItem> PAYLOAD_DEMO =
            ITEMS.registerSimpleBlockItem("payload_demo", PayloadDemoBlocks.PAYLOAD_DEMO);
}
