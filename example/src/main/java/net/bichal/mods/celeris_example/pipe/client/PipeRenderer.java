package net.bichal.mods.celeris_example.pipe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.bichal.mods.celeris.api.transit.ClientItemTable;
import net.bichal.mods.celeris.api.transit.TransitMotion;
import net.bichal.mods.celeris_example.CelerisExample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws every item in the pipes straight from {@link PipeClientState}: one
 * closed-form position per item per frame ({@link TransitMotion}), no entity,
 * no per-tick update. The renderer never touches the server's data, only what
 * the delta and sync messages told the client.
 */
// Compatibility with 1.21
@SuppressWarnings("removal")
@EventBusSubscriber(modid = CelerisExample.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PipeRenderer {

    private static final float ITEM_SCALE = 0.3f;
    private static final float[] POSITION = new float[3];

    private PipeRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !PipeClientState.isSynced()) {
            return;
        }
        ClientItemTable table = PipeClientState.ITEMS;
        if (table.size() == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        double now = PipeClientState.now();

        for (int slot = 0, limit = table.slotLimit(); slot < limit; slot++) {
            if (!table.isLive(slot)) {
                continue;
            }
            long[] shape = PipeClientState.SEGMENTS.get(table.segmentAt(slot));
            int itemId = PipeClientState.ITEM_OF_HANDLE.getOrDefault(table.itemAt(slot), -1);
            if (shape == null || itemId < 0) {
                continue;
            }
            float progress = table.progressAt(slot, now, shape.length - 1);
            TransitMotion.position(shape, shape.length, table.forwardAt(slot), progress, POSITION);

            pose.pushPose();
            pose.translate(POSITION[0] - camera.x, POSITION[1] - camera.y, POSITION[2] - camera.z);
            pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            BlockPos at = BlockPos.containing(POSITION[0], POSITION[1], POSITION[2]);
            minecraft.getItemRenderer().renderStatic(new ItemStack(BuiltInRegistries.ITEM.byId(itemId)),
                    ItemDisplayContext.FIXED, LevelRenderer.getLightColor(level, at), OverlayTexture.NO_OVERLAY,
                    pose, buffers, level, slot);
            pose.popPose();
        }
        buffers.endBatch();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PipeClientState.clear();
    }
}
