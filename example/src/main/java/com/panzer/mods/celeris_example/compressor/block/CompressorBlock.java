package com.panzer.mods.celeris_example.compressor.block;

import com.mojang.serialization.MapCodec;
import com.panzer.mods.celeris_example.compressor.menu.CompressorMenu;
import com.panzer.mods.celeris_example.compressor.registry.CompressorBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CompressorBlock extends BaseEntityBlock {

    public CompressorBlock(Properties props) {
        super(props);
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return BlockBehaviour.simpleCodec(CompressorBlock::new);
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new CompressorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, CompressorBlockEntities.COMPRESSOR.get(), CompressorBlockEntity::tick);
    }

    @Override
    public @NotNull InteractionResult useWithoutItem(
            @NotNull BlockState state, Level level, @NotNull BlockPos pos,
            @NotNull Player player, @NotNull BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            MenuProvider provider = getMenuProvider(state, level, pos);
            if (provider != null) sp.openMenu(provider);
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public MenuProvider getMenuProvider(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CompressorBlockEntity be)) return null;
        return new SimpleMenuProvider(
                (id, inv, ignored) -> new CompressorMenu(
                        id, inv, be,
                        ContainerLevelAccess.create(level, pos)),
                net.minecraft.network.chat.Component.translatable("menu.title.celeris_example.compressor")
        );
    }

    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level level,
                        @NotNull BlockPos pos, @NotNull BlockState old, boolean piston) {
        super.onPlace(state, level, pos, old, piston);
        level.invalidateCapabilities(pos);
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level,
                         @NotNull BlockPos pos, @NotNull BlockState next, boolean piston) {
        super.onRemove(state, level, pos, next, piston);
        level.invalidateCapabilities(pos);
    }
}
