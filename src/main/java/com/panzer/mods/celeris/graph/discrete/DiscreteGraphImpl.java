package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.dirty.DirtyQueue;
import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopology;
import com.panzer.mods.celeris.core.topology.NodeId;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link DiscreteGraph}: wires {@link NetworkTopology} (shared with
 * the segmented pipeline), {@link DiscreteNodeStore}, a {@link DirtyQueue}
 * and a {@link TopologicalDAGSolver} together, and translates the public
 * NodeId-based API to the dense-index internals those pieces operate on.
 *
 * <p>{@link #solver()} and {@link #dirtyQueue()} are exposed so {@code
 * pipeline.discrete.EventDrivenPipelineSolverImpl} can be constructed
 * against these exact instances -- topology/execution are orthogonal
 * concerns (see the architecture doc §5), so this class never calls
 * {@code propagate()} itself; only the pipeline layer drives ticking.
 */
public final class DiscreteGraphImpl implements DiscreteGraph {

    private final NetworkTopology topology;
    private final DiscreteNodeStore nodes;
    private final DirtyQueue dirtyQueue;
    private final TopologicalDAGSolver solver;
    private final List<DiscreteGraphListener> listeners = new ArrayList<>(2);

    public DiscreteGraphImpl(NetworkTopology topology, DiscreteNodeStore nodes,
                              DirtyQueue dirtyQueue, PropagationBudget budget) {
        this.topology = topology;
        this.nodes = nodes;
        this.dirtyQueue = dirtyQueue;
        this.solver = new TopologicalDAGSolver(topology, nodes, dirtyQueue, budget);
        this.solver.setLevelChangeSink(this::onChanged);
    }

    /** The solver this graph feeds -- shared with the pipeline layer so both drive the same state. */
    public TopologicalDAGSolver solver() {
        return solver;
    }

    public DirtyQueue dirtyQueue() {
        return dirtyQueue;
    }

    @Override
    public void addNode(NodeId id, ConnectionMask connections, boolean isSource) {
        int[] before = neighborScratch;
        int beforeCount = neighborsBefore(id, before);

        topology.addNode(id, connections);
        int idx = topology.denseIndexOf(id);
        nodes.ensureCapacity(idx + 1);
        nodes.clear(idx);
        nodes.setSource(idx, isSource);

        dirtyQueue.markDirty(idx);
        markAll(before, beforeCount);
        markCurrentNeighborsDirty(idx);
    }

    @Override
    public void removeNode(NodeId id) {
        int idx = topology.denseIndexOf(id);
        if (idx < 0) {
            return;
        }
        int[] before = neighborScratch;
        int beforeCount = topology.neighborDenseIndices(idx, before);

        topology.removeNode(id);
        nodes.clear(idx);

        markAll(before, beforeCount);
    }

    @Override
    public void updateConnections(NodeId id, ConnectionMask connections) {
        int idx = topology.denseIndexOf(id);
        if (idx < 0) {
            addNode(id, connections, false);
            return;
        }
        int[] before = neighborScratch;
        int beforeCount = topology.neighborDenseIndices(idx, before);
        topology.updateConnections(id, connections);

        dirtyQueue.markDirty(idx);
        markAll(before, beforeCount);
        markCurrentNeighborsDirty(idx);
    }

    @SuppressWarnings("MathClampMigration")
    @Override
    public void setSourceLevel(NodeId id, int level) {
        int idx = topology.denseIndexOf(id);
        if (idx < 0) {
            return;
        }
        nodes.setSource(idx, true);
        nodes.setSignalLevel(idx, Math.max(0, Math.min(15, level)));
        dirtyQueue.markDirty(idx);
        markCurrentNeighborsDirty(idx);
    }

    @Override
    public int signalLevelAt(NodeId id) {
        int idx = topology.denseIndexOf(id);
        return idx < 0 ? 0 : nodes.signalLevel(idx);
    }

    @Override
    public void addListener(DiscreteGraphListener listener) {
        listeners.add(listener);
    }

    // Mutations are server-thread only (same contract as NetworkTopologyImpl),
    // so one reusable 6-slot scratch replaces a List<Integer> per call.
    private final int[] neighborScratch = new int[NetworkTopology.MAX_NEIGHBORS];
    private final int[] currentScratch = new int[NetworkTopology.MAX_NEIGHBORS];

    /** Neighbors of a node that may not be registered yet (addNode path); 0 if unknown. */
    private int neighborsBefore(NodeId id, int[] out) {
        int idx = topology.denseIndexOf(id);
        return idx < 0 ? 0 : topology.neighborDenseIndices(idx, out);
    }

    private void markAll(int[] indices, int count) {
        for (int i = 0; i < count; i++) {
            dirtyQueue.markDirty(indices[i]);
        }
    }

    private void markCurrentNeighborsDirty(int denseIndex) {
        int[] cur = currentScratch;
        int n = topology.neighborDenseIndices(denseIndex, cur);
        markAll(cur, n);
    }

    private void onChanged(int denseIndex, int oldLevel, int newLevel) {
        NodeId id = topology.nodeIdAt(denseIndex);
        if (id == null) {
            return; // node was removed between marking dirty and this pass evaluating it
        }
        for (DiscreteGraphListener listener : listeners) {
            listener.onLevelChanged(id, oldLevel, newLevel);
        }
    }

    /** Internal callback contract between {@link TopologicalDAGSolver} and this class -- not part of the public API. */
    @FunctionalInterface
    public interface LevelChangeSink {
        void onChanged(int denseIndex, int oldLevel, int newLevel);
    }
}
