package com.panzer.mods.celeris_example.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.compressor.registry.CompressorItems;
import com.panzer.mods.celeris_example.memorybank.registry.MemoryBankItems;
import com.panzer.mods.celeris_example.payloaddemo.registry.PayloadDemoItems;
import com.panzer.mods.celeris_example.pipe.registry.PipeItems;
import com.panzer.mods.celeris_example.ringbuffer.registry.RingBufferDemoItems;
import com.panzer.mods.celeris_example.wire.registry.WireItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CelerisCreativeTabs {

    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CelerisExample.MOD_ID);

    @SuppressWarnings("unused")
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register(
            CelerisExample.MOD_ID + "_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CelerisExample.MOD_ID + ".tab"))
                    .icon(() -> new ItemStack(CompressorItems.COMPRESSOR.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(CompressorItems.COMPRESSOR.get());
                        output.accept(MemoryBankItems.MEMORY_BANK.get());
                        output.accept(PayloadDemoItems.PAYLOAD_DEMO.get());
                        output.accept(PipeItems.PIPE.get());
                        output.accept(RingBufferDemoItems.RING_BUFFER_DEMO.get());
                        output.accept(WireItems.WIRE.get());
                    })
                    .build()
    );

    private CelerisCreativeTabs() {
    }

    public static void register(IEventBus eventBus) {
        CREATIVE_TABS.register(eventBus);
    }
}
