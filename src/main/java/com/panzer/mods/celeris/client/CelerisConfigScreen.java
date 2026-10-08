//? if fabric {
/*package com.panzer.mods.celeris.client;

import com.panzer.mods.celeris.config.CelerisFabricConfig;
import com.panzer.mods.celeris.config.CelerisSettings;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/^*
 * Celeris's settings on Fabric, opened from Mod Menu ({@code CelerisModMenu}).
 * Built from vanilla widgets only; the value is saved when the screen closes.
 * NeoForge uses its own generated screen for {@code CelerisConfig} instead.
 ^/
public final class CelerisConfigScreen extends Screen {

    private static final int WIDTH = 200;
    private static final int RANGE = CelerisSettings.MAX_COMPRESSION_LEVEL - CelerisSettings.MIN_COMPRESSION_LEVEL;

    private final Screen parent;
    private int level = CelerisSettings.compressionLevel();

    public CelerisConfigScreen(Screen parent) {
        super(Component.translatable("celeris.configuration.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - 30;
        addRenderableWidget(new StringWidget(x, y - 24, WIDTH, 9, this.title, this.font));
        LevelSlider slider = new LevelSlider(x, y, WIDTH, 20);
        slider.setTooltip(Tooltip.create(Component.translatable("celeris.configuration.compressionLevel.tooltip")));
        addRenderableWidget(slider);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y + 28, WIDTH, 20)
                .build());
    }

    @Override
    public void onClose() {
        CelerisFabricConfig.setCompressionLevel(this.level);
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static Component levelLabel(int level) {
        Component value = level == 0
                ? Component.translatable("celeris.configuration.compressionLevel.off")
                : Component.literal(Integer.toString(level));
        return Component.translatable("celeris.configuration.compressionLevel.value",
                Component.translatable("celeris.configuration.compressionLevel"), value);
    }

    private final class LevelSlider extends AbstractSliderButton {

        LevelSlider(int x, int y, int width, int height) {
            super(x, y, width, height, levelLabel(level),
                    (double) (level - CelerisSettings.MIN_COMPRESSION_LEVEL) / RANGE);
        }

        @Override
        protected void updateMessage() {
            setMessage(levelLabel(level));
        }

        @Override
        protected void applyValue() {
            level = CelerisSettings.MIN_COMPRESSION_LEVEL + (int) Math.round(this.value * RANGE);
        }
    }
}
*///?}
