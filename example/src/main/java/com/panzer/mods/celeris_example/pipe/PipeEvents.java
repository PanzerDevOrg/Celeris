package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Drives {@link PipeNetworkManager} from the game's tick/level-lifecycle events. */
@SuppressWarnings("removal") // Compatibility with 1.21
@EventBusSubscriber(modid = CelerisExample.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class PipeEvents {

    private PipeEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            PipeNetworkManager.tickIfPresent(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            PipeNetworkManager.unload(serverLevel);
        }
    }
}
