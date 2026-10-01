package com.panzer.mods.celeris_example.ringbuffer;

import com.panzer.mods.celeris_example.CelerisExample;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Drains {@link RingBufferDemo} once per server tick and shuts it down cleanly on server stop. */
@SuppressWarnings("removal") // Compatibility for 1.21
@EventBusSubscriber(modid = CelerisExample.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class RingBufferDemoEvents {

    private RingBufferDemoEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        RingBufferDemo.drainTick();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RingBufferDemo.shutdown();
    }
}
