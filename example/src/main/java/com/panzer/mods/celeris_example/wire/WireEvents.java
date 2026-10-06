package com.panzer.mods.celeris_example.wire;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives {@link WireNetworkManager} from the game's own tick/level-lifecycle
 * events -- game-bus events, not mod-bus, so this uses the default
 * {@link EventBusSubscriber.Bus#GAME}.
 */
@SuppressWarnings("removal") // Compatibility for 1.21
@EventBusSubscriber(modid = CelerisExample.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class WireEvents {

    private WireEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            WireNetworkManager.tickIfPresent(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            WireNetworkManager.unload(serverLevel);
        }
    }
}
