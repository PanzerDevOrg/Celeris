package com.panzer.mods.celeris_example.compressor;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.compressor.menu.CompressorScreen;
import com.panzer.mods.celeris_example.compressor.registry.CompressorMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

// Compatibilty for 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CelerisExample.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CompressorClientSetup {
    private CompressorClientSetup() {}

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent e) {
        e.register(CompressorMenus.COMPRESSOR.get(), CompressorScreen::new);
    }
}
