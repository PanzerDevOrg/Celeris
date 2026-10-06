package com.panzer.mods.celeris_example.memorybank;

import com.mojang.serialization.MapCodec;
import com.panzer.mods.celeris.core.memory.MemoryBus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/**
 * Right-click to increment an off-heap counter (see {@link MemoryBankBlockEntity})
 * and see it in chat. Break it and place a new one where it was (or reload
 * the world) -- the count survives, round-tripped through NBT on top of
 * {@link MemoryBus}.
 */
public final class MemoryBankBlock extends BaseEntityBlock {

    public MemoryBankBlock(Properties props) {
        super(props);
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return BlockBehaviour.simpleCodec(MemoryBankBlock::new);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new MemoryBankBlockEntity(pos, state);
    }

    @Override
    public @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player, @NotNull BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MemoryBankBlockEntity be) {
            be.incrementCounter();
            player.displayClientMessage(
                    Component.literal("Celeris MemoryBus off-heap counter: " + be.counter()), true);
        }
        return InteractionResult.SUCCESS;
    }
}
