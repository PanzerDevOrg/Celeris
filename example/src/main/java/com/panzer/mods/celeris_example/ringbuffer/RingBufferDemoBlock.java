package com.panzer.mods.celeris_example.ringbuffer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/** Right-click fires a burst of concurrent publishes into {@link RingBufferDemo} and reports the running total drained so far. */
public final class RingBufferDemoBlock extends Block {

    public RingBufferDemoBlock(Properties props) {
        super(props);
    }

    @Override
    public @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player, @NotNull BlockHitResult hit) {
        if (!level.isClientSide) {
            RingBufferDemo.fireBurst();
            player.displayClientMessage(Component.literal(
                    "Celeris MpscRingBuffer: fired 8 concurrent publishes. Running total drained so far: "
                            + RingBufferDemo.runningTotal()), true);
        }
        return InteractionResult.SUCCESS;
    }
}
