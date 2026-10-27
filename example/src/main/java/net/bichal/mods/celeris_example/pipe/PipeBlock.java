package net.bichal.mods.celeris_example.pipe;

import net.bichal.mods.celeris.core.topology.ConnectionMask;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraph;
import net.bichal.mods.celeris.pipeline.transit.TransitSolver;
import net.bichal.mods.celeris_example.wire.WireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/**
 * A pipe segment that carries items, wired to {@link PipeNetworkManager} -- a
 * working demo of {@link SegmentedGraph} and {@link TransitSolver}.
 * Place a straight run of these and they fuse into a single {@code
 * GraphSegment} automatically; place a T-junction or corner and watch it
 * split into separate segments at the junction. Right-click a pipe end or
 * junction with an item to put it in: it travels the pipe, is shown to every
 * player without an entity per item, and is handed to an inventory at the end
 * of the pipe. {@link #FULL} lights up while the inventory refuses and the
 * pipe is backed up.
 *
 * <p>Same no-block-entity design as {@link WireBlock}
 * -- the items live in each segment's {@code TransitPayload}, owned by
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
    protected @NotNull ItemInteractionResult useItemOn(@NotNull ItemStack stack, @NotNull BlockState state,
                                                       @NotNull Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player, @NotNull InteractionHand hand,
                                                       @NotNull BlockHitResult hit) {
        if (stack.isEmpty() || stack.getItem() == asItem()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel serverLevel) {
            if (PipeNetworkManager.forLevel(serverLevel).insert(pos, hit.getDirection(), stack)) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            } else {
                player.displayClientMessage(Component.translatable("message.celeris_example.pipe_no_entry"), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
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
