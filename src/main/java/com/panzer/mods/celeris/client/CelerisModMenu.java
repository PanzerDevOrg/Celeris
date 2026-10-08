//? if fabric {
/*package com.panzer.mods.celeris.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/^*
 * Mod Menu entrypoint (fabric.mod.json "modmenu"): the Config button next to
 * Celeris in Mod Menu's list. Mod Menu is optional; without it this class is
 * never loaded.
 ^/
public final class CelerisModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CelerisConfigScreen::new;
    }
}
*///?}
