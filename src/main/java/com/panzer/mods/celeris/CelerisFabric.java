//? if fabric {
/*package com.panzer.mods.celeris;

import com.panzer.mods.celeris.network.NetworkRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

/^*
 * Mod entry points on Fabric (fabric.mod.json: "main" and "client"). The same
 * startup as NeoForge's {@link Celeris}: backend and codec selection, then the
 * network channel.
 ^/
public final class CelerisFabric implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        Celeris.start();
        NetworkRegistry.register();
    }

    @Override
    public void onInitializeClient() {
        NetworkRegistry.registerClient();
    }
}
*///?}
