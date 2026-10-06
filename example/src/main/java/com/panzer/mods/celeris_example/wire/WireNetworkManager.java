package com.panzer.mods.celeris_example.wire;

import com.panzer.mods.celeris.core.dirty.DirtyQueue;
import com.panzer.mods.celeris.core.dirty.LongRingDirtyQueue;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.topology.*;
import com.panzer.mods.celeris.graph.discrete.*;
import com.panzer.mods.celeris.pipeline.discrete.EventDrivenPipelineSolver;
import com.panzer.mods.celeris.pipeline.discrete.EventDrivenPipelineSolverImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * One {@link DiscreteGraph} + {@link EventDrivenPipelineSolver} per dimension,
 * driven once per tick by {@link WireEvents}. This is the whole wiring
 * a real mod needs: a level key, a place to construct the graph/solver pair
 * once, and a listener that turns a signal-level change back into a
 * visible block update.
 * <p>
 * {@link #forLevel} lazily creates an instance per dimension and keeps
 * it for the dimension's lifetime; {@link #unload} tears it down when the
 * level unloads, freeing {@link DiscreteNodeStore}'s off-heap allocation.
 */
public final class WireNetworkManager implements AutoCloseable {

    private static final int INITIAL_NODE_CAPACITY = 256;
    private static final int SOURCE_LEVEL = 15;

    private static final Map<ResourceKey<Level>, WireNetworkManager> INSTANCES = new HashMap<>();

    public static WireNetworkManager forLevel(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignoredKey -> new WireNetworkManager(level));
    }

    public static void tickIfPresent(ServerLevel level) {
        WireNetworkManager manager = INSTANCES.get(level.dimension());
        if (manager != null) {
            manager.tick();
        }
    }

    public static void unload(ServerLevel level) {
        WireNetworkManager removed = INSTANCES.remove(level.dimension());
        if (removed != null) {
            removed.close();
        }
    }

    private final ServerLevel level;
    private final NetworkTopology topology;
    private final DiscreteNodeStore nodes;
    private final DiscreteGraphImpl graph;
    private final EventDrivenPipelineSolver solver;

    private WireNetworkManager(ServerLevel level) {
        this.level = level;
        this.topology = new NetworkTopologyImpl();
        this.nodes = new DiscreteNodeStore(CelerisRuntime.backend(), INITIAL_NODE_CAPACITY);
        DirtyQueue dirtyQueue = new LongRingDirtyQueue();
        this.graph = new DiscreteGraphImpl(topology, nodes, dirtyQueue, PropagationBudget.standard());
        this.solver = new EventDrivenPipelineSolverImpl(graph.solver(), graph.dirtyQueue());
        this.graph.addListener(this::onSignalChanged);
    }

    public void addWire(BlockPos pos, ConnectionMask connections) {
        graph.addNode(NodeIdCodec.of(pos.asLong()), connections, false);
    }

    public void removeWire(BlockPos pos) {
        graph.removeNode(NodeIdCodec.of(pos.asLong()));
    }

    /**
     * Skips the graph call entirely when the mask hasn't actually changed --
     * without this, every {@link #onSignalChanged} write triggers vanilla's
     * {@code neighborChanged} on adjacent wires, which would otherwise call
     * back in here and mark them dirty again for no topological reason,
     * costing an extra propagation pass per tick for nothing.
     */
    public void updateWireConnections(BlockPos pos, ConnectionMask connections) {
        NodeId id = NodeIdCodec.of(pos.asLong());
        if (topology.connectionsOf(id).equals(connections)) {
            return;
        }
        graph.updateConnections(id, connections);
    }

    /** Right-click behavior: flips a wire between an off source (0) and a full source (15). */
    public void toggleSource(BlockPos pos) {
        NodeId id = NodeIdCodec.of(pos.asLong());
        int current = graph.signalLevelAt(id);
        graph.setSourceLevel(id, current > 0 ? 0 : SOURCE_LEVEL);
    }

    private void tick() {
        solver.tick();
    }

    /** {@link DiscreteGraphListener} callback -- the only place this example touches a BlockState. */
    private void onSignalChanged(NodeId id, int oldLevel, int newLevel) {
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(id));
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof WireBlock && state.getValue(WireBlock.POWER) != newLevel) {
            level.setBlock(pos, state.setValue(WireBlock.POWER, newLevel), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void close() {
        nodes.close();
    }
}
