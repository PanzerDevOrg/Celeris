package com.panzer.mods.celeris_example.compressor.menu;

import com.panzer.mods.celeris_example.CelerisExample;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

public final class CompressorScreen extends AbstractContainerScreen<CompressorMenu> {

    public static final ResourceLocation COMPRESSOR_SCREEN_LOCATION =
            ResourceLocation.fromNamespaceAndPath(CelerisExample.MOD_ID, "textures/gui/container/generic_screen.png");

    private static final int BAR_WIDTH = 128;

    private long durationMs = 10_000L; // 10s
    private boolean active = true;

    private long lastTimeMs;
    private float progress = 0.0f; // 0.0f to 1.0f
    private boolean forward = true;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private Button toggleBtn;

    public CompressorScreen(CompressorMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        this.lastTimeMs = Util.getMillis();

        int barX = leftPos + 24;

        int btnMinusW = 20;
        int btnToggleW = 40;
        int btnPlusW = 20;

        // 128 - 80 = 48
        int totalButtonsW = btnMinusW + btnToggleW + btnPlusW; // 80px
        int gap = (BAR_WIDTH - totalButtonsW) / 2; // 24px padding

        int xToggle = barX + btnMinusW + gap; // leftPos + 24 + 20 + 24 = leftPos + 52
        int xPlus = xToggle + btnToggleW + gap; // leftPos + 52 + 40 + 24 = leftPos + 116 (end in leftPos + 136)

        int startY = topPos + 46;

        // -
        addRenderableWidget(Button.builder(Component.literal("-"), btn -> {
            this.durationMs = Math.min(30_000L, this.durationMs + 2_000L); // Max 30s
        }).bounds(barX, startY, btnMinusW, 20).build());

        // ON / OFF
        toggleBtn = addRenderableWidget(Button.builder(getToggleText(), btn -> {
            this.active = !this.active;
            btn.setMessage(getToggleText());
        }).bounds(xToggle, startY, btnToggleW, 20).build());

        // +
        addRenderableWidget(Button.builder(Component.literal("+"), btn -> {
            this.durationMs = Math.max(1_000L, this.durationMs - 2_000L); // Min 1s
        }).bounds(xPlus, startY, btnPlusW, 20).build());
    }

    private Component getToggleText() {
        return Component.literal(this.active ? "ON" : "OFF");
    }

    private void updateProgress() {
        long now = Util.getMillis();
        long delta = now - lastTimeMs;
        lastTimeMs = now;

        if (!active || durationMs <= 0) return;

        float step = (float) delta / durationMs;

        if (forward) {
            progress += step;
            if (progress >= 1.0f) {
                progress = 1.0f;
                forward = false;
            }
        } else {
            progress -= step;
            if (progress <= 0.0f) {
                progress = 0.0f;
                forward = true;
            }
        }
    }

    private int getStepPercent() {
        return Math.clamp(Math.round(progress * 100f), 0, 100);
    }

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        updateProgress();

        int pct = getStepPercent();
        int fillWidth = (pct * BAR_WIDTH) / 100;

        int screenX = leftPos;
        int screenY = topPos;

        g.blit(COMPRESSOR_SCREEN_LOCATION, screenX, screenY, 0, 0, this.imageWidth, this.imageHeight);

        int x = leftPos + 24;
        int y = topPos + 20;
        int barHeight = y + 10;

        g.fill(x, y, x + fillWidth, barHeight, 0xFFFAFAFA);
        g.fill(x + fillWidth, y, x + BAR_WIDTH, barHeight, 0xFF3F3F3F);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics g, int mx, int my) {
        super.renderLabels(g, mx, my);

        int pct = getStepPercent();
        g.drawString(font, pct + "% (" + (durationMs / 1000f) + "s)", 24, 32, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        renderTooltip(g, mx, my);
    }
}
