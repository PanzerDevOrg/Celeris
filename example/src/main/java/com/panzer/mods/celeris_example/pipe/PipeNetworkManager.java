package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris.core.topology.*;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraph;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import com.panzer.mods.celeris.pipeline.continuous.SIMDPipelineSolver;
import com.panzer.mods.celeris.pipeline.continuous.SIMDPipelineSolverImpl;
import com.panzer.mods.celeris.pipeline.continuous.SegmentTickBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * One {@link SegmentedGraph} + {@link SIMDPipelineSolver} per dimension,
 * driven once per tick by {@link PipeEvents}.
 *
 * <p>Unlike {@link com.panzer.mods.celeris_example.wire.WireNetworkManager},
 * {@code SegmentedGraph} has no change listener -- segment fusion is
 * queried, not pushed. So after each {@link #tick}, this class walks
 * {@link SegmentedGraph#segments()} itself and updates each segment's
 * endpoint blocks; a real mod would do the same for its own visual/sync
 * needs (or skip it entirely and only read segment state on demand, e.g.
 * when a player opens a GUI).
 */
public final class PipeNetworkManager implements AutoCloseable {

    private static final Map<ResourceKey<Level>, PipeNetworkManager> INSTANCES = new HashMap<>();

    public static PipeNetworkManager forLevel(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignoredKey -> new PipeNetworkManager(level));
    }

    public static void tickIfPresent(ServerLevel level) {
        PipeNetworkManager manager = INSTANCES.get(level.dimension());
        if (manager != null) {
            manager.tick();
        }
    }

    public static void unload(ServerLevel level) {
        PipeNetworkManager removed = INSTANCES.remove(level.dimension());
        if (removed != null) {
            removed.close();
        }
    }

    private final ServerLevel level;
    private final NetworkTopology topology;
    private final SegmentedGraph<FluidPayload> graph;
    private final SIMDPipelineSolver<FluidPayload> solver;

    private PipeNetworkManager(ServerLevel level) {
        this.level = level;
        this.topology = new NetworkTopologyImpl();
        this.graph = new SegmentedGraphImpl<>(topology, FluidPayload::new);
        SIMDPipelineSolverImpl<FluidPayload> solverImpl = new SIMDPipelineSolverImpl<>();
        solverImpl.bind(graph, new SegmentTickBatch.Extractor<>() {
            @Override
            public void gather(GraphSegment<FluidPayload> segment, SegmentTickBatch batch) {
                FluidPayload payload = segment.payload();
                batch.add(payload.flowRate(), payload.bufferLevel());
            }

            @Override
            public void scatter(GraphSegment<FluidPayload> segment, int index, SegmentTickBatch batch) {
                FluidPayload payload = segment.payload();
                payload.setBufferLevel(batch.bufferLevels()[index]);
                payload.setOverflowing(batch.overflowMask()[index]);
            }
        });
        this.solver = solverImpl;
    }

    public void addPipe(BlockPos pos, ConnectionMask connections) {
        graph.addNode(NodeIdCodec.of(pos.asLong()), connections);
    }

    public void removePipe(BlockPos pos) {
        graph.removeNode(NodeIdCodec.of(pos.asLong()));
    }

    /** Skips the graph call (and the fusion re-trace it triggers) when the mask hasn't actually changed -- see {@code WireNetworkManager#updateWireConnections} for why this guard matters. */
    public void updatePipeConnections(BlockPos pos, ConnectionMask connections) {
        NodeId id = NodeIdCodec.of(pos.asLong());
        if (topology.connectionsOf(id).equals(connections)) {
            return;
        }
        graph.updateConnections(id, connections);
    }

    private void tick() {
        solver.tick();
        refreshFullState();
    }

    /** Pull-model visual sync: {@link SegmentedGraph} has no push listener, so this walks every segment after each tick. */
    private void refreshFullState() {
        for (GraphSegment<FluidPayload> segment : graph.segments()) {
            boolean full = segment.payload().isOverflowing();
            applyFullState(segment.nodeA(), full);
            applyFullState(segment.nodeB(), full);
        }
    }

    private void applyFullState(NodeId id, boolean full) {
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(id));
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof PipeBlock && state.getValue(PipeBlock.FULL) != full) {
            level.setBlock(pos, state.setValue(PipeBlock.FULL, full), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void close() {
        // SegmentedGraphImpl holds no off-heap allocation of its own (unlike
        // DiscreteNodeStore) -- segment/payload state is plain Java objects,
        // so there's nothing to free here. Kept as a no-op AutoCloseable for
        // symmetry with WireNetworkManager and in case a future payload type
        // (e.g. one backed by MemoryBus) needs it.
    }
}
