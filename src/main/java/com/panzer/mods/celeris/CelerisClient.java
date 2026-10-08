//? if neoforge {
package com.panzer.mods.celeris;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only part of the NeoForge entry point: the Config button in the mod
 * list, which opens NeoForge's generated screen for {@code CelerisConfig}.
 */
@Mod(value = Celeris.MOD_ID, dist = Dist.CLIENT)
public final class CelerisClient {

    public CelerisClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
//?}
