package com.panzer.mods.celeris_example.wire;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.graph.discrete.DiscreteGraph;
import com.panzer.mods.celeris.graph.discrete.DiscreteNodeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/**
 * A signal wire, wired to {@link WireNetworkManager} -- a real, working
 * demo of {@link DiscreteGraph}:
 * every wire is an individually-addressable node, connects to adjacent
 * wires automatically, and right-clicking one toggles it as a signal
 * source (level 15). Watch {@code POWER} propagate and attenuate through a
 * run of wires, same 15-to-0 falloff as vanilla redstone.
 *
 * <p>No block entity -- all state (signal level, source flag) lives
 * off-heap in {@link DiscreteNodeStore},
 * owned by {@link WireNetworkManager}. This block only translates
 * placement/removal/neighbor events into graph calls and mirrors the
 * result back onto {@link #POWER} for rendering.
 */
public final class WireBlock extends Block {

    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);

    public WireBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
    }

    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                         @NotNull BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state.is(oldState.getBlock())) {
            return; // only a property change (e.g. our own POWER update), not a fresh placement
        }
        if (level instanceof ServerLevel serverLevel) {
            WireNetworkManager.forLevel(serverLevel).addWire(pos, connectionsAt(level, pos));
        }
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                          @NotNull BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            WireNetworkManager.forLevel(serverLevel).removeWire(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                 @NotNull Block neighborBlock, @NotNull BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            WireNetworkManager.forLevel(serverLevel).updateWireConnections(pos, connectionsAt(level, pos));
        }
    }

    @Override
    public @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player, @NotNull BlockHitResult hit) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            WireNetworkManager.forLevel(serverLevel).toggleSource(pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** Which of the 6 neighbors are also a {@link WireBlock} -- direction ordinals match Celeris's own DOWN/UP/N/S/W/E layout, so no translation table is needed. */
    private static ConnectionMask connectionsAt(Level level, BlockPos pos) {
        ConnectionMask mask = ConnectionMask.NONE;
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).getBlock() instanceof WireBlock) {
                mask = mask.with(dir.ordinal());
            }
        }
        return mask;
    }
}
