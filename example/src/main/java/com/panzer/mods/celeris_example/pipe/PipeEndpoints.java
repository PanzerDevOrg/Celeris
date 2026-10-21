package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.core.topology.NodeIdCodec;
import com.panzer.mods.celeris.pipeline.transit.TransitEndpoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Inventories next to the end of a pipe are the terminals of the network: an
 * item that reaches the end is offered to the inventory's item handler, and
 * waits (blocking the pipe behind it) while the inventory refuses.
 *
 * <p>The graph only knows pipes; a chest beside a pipe is not a node. That is
 * exactly what {@link TransitEndpoints} is for.
 */
final class PipeEndpoints implements TransitEndpoints {

    private final ServerLevel level;
    private final PipeItemTable items;

    PipeEndpoints(ServerLevel level, PipeItemTable items) {
        this.level = level;
        this.items = items;
    }

    @Override
    public int terminalMask(long nodeRaw) {
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(new NodeId(nodeRaw)));
        int mask = 0;
        for (Direction dir : Direction.values()) {
            if (handlerAt(pos.relative(dir), dir.getOpposite()) != null) {
                mask |= 1 << dir.ordinal();
            }
        }
        return mask;
    }

    @Override
    public boolean deliver(long nodeRaw, int direction, int handle) {
        Direction dir = Direction.values()[direction];
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(new NodeId(nodeRaw)));
        IItemHandler handler = handlerAt(pos.relative(dir), dir.getOpposite());
        if (handler == null) {
            return false;
        }
        ItemStack stack = items.get(handle);
        if (!ItemHandlerHelper.insertItem(handler, stack, true).isEmpty()) {
            return false;
        }
        ItemHandlerHelper.insertItem(handler, stack, false);
        items.release(handle);
        return true;
    }

    private IItemHandler handlerAt(BlockPos pos, Direction side) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
    }
}
