package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraph;
import com.panzer.mods.celeris.pipeline.continuous.SIMDPipelineSolver;
import com.panzer.mods.celeris_example.wire.WireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.NotNull;

/**
 * A pipe segment, wired to {@link PipeNetworkManager} -- a real, working
 * demo of {@link SegmentedGraph}
 * and {@link SIMDPipelineSolver}.
 * Place a straight run of these and they fuse into a single {@code
 * GraphSegment} automatically; place a T-junction or corner and watch it
 * split into separate segments at the junction. {@link #FULL} lights up
 * once a segment's buffer fills past capacity -- every fused segment ticks
 * through one shared SIMD batch, not one calculation per pipe block.
 *
 * <p>Same no-block-entity design as {@link WireBlock}
 * -- segment state lives in each segment's {@link FluidPayload}, owned by
 * {@link PipeNetworkManager}, not in a per-block-position block entity.
 */
public final class PipeBlock extends Block {

    public static final BooleanProperty FULL = BooleanProperty.create("full");

    public PipeBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any().setValue(FULL, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FULL);
    }

    @Override
    public void onPlace(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                         @NotNull BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state.is(oldState.getBlock())) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            PipeNetworkManager.forLevel(serverLevel).addPipe(pos, connectionsAt(level, pos));
        }
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                          @NotNull BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            PipeNetworkManager.forLevel(serverLevel).removePipe(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void neighborChanged(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                                 @NotNull Block neighborBlock, @NotNull BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            PipeNetworkManager.forLevel(serverLevel).updatePipeConnections(pos, connectionsAt(level, pos));
        }
    }

    /** Which of the 6 neighbors are also a {@link PipeBlock}. */
    private static ConnectionMask connectionsAt(Level level, BlockPos pos) {
        ConnectionMask mask = ConnectionMask.NONE;
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).getBlock() instanceof PipeBlock) {
                mask = mask.with(dir.ordinal());
            }
        }
        return mask;
    }
}
