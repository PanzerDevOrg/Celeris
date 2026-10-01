package com.panzer.mods.celeris_example.compressor.registry;

import com.panzer.mods.celeris_example.CelerisExample;
import com.panzer.mods.celeris_example.compressor.menu.CompressorMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CompressorMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(BuiltInRegistries.MENU, CelerisExample.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<CompressorMenu>> COMPRESSOR =
            MENU_TYPES.register("compressor", () ->
                    new MenuType<>(CompressorMenu::new, FeatureFlags.DEFAULT_FLAGS)
            );
}
