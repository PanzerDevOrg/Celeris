package com.panzer.mods.celeris.core.topology;

/**
 * Pure physical connectivity: which NodeIds are adjacent to which, and what
 * each node's ConnectionMask is. Knows nothing about segments, payloads,
 * signal levels, or fusion -- that is exactly the boundary that lets both
 * graph/segmented (which fuses straight runs on top of this) and
 * graph/discrete (which evaluates every node individually on top of this)
 * share one adjacency structure instead of each maintaining their own.
 *
 * <p>Mutations (addNode/removeNode/updateConnections) describe physical
 * topology changes only; segment fusion and per-node evaluation are layered
 * on top by the graph implementations.
 */
public interface NetworkTopology {

    void addNode(NodeId id, ConnectionMask connections);

    void removeNode(NodeId id);

    void updateConnections(NodeId id, ConnectionMask connections);

    /** {@code -1} if id is not a registered node. */
    int denseIndexOf(NodeId id);

    /** The NodeId currently occupying a dense index, or {@code null} if the index is not live. */
    NodeId nodeIdAt(int denseIndex);

    /** {@link ConnectionMask#NONE} if id is not a registered node. */
    ConnectionMask connectionsOf(NodeId id);

    /** Walks reciprocal neighbors of id without allocating a collection. */
    NeighborCursor neighborsOf(NodeId id);

    /**
     * Same as {@link #neighborsOf(NodeId)} but for a caller that already
     * holds a dense index (the common case in a hot loop, resolved once
     * via {@link #denseIndexOf}) -- skips the extra id-to-index lookup
     * {@link #neighborsOf(NodeId)} would otherwise pay internally.
     */
    NeighborCursor neighborsOfDense(int denseIndex);

    /**
     * Allocation-free bulk form of {@link #neighborsOfDense}: writes the
     * dense indices of all reciprocal neighbors into {@code out} (which
     * must hold at least {@link #MAX_NEIGHBORS} ints) and returns how many
     * were written. Preferred in hot loops -- no cursor object, no
     * {@code NodeId} instances.
     */
    default int neighborDenseIndices(int denseIndex, int[] out) {
        int n = 0;
        NeighborCursor cursor = neighborsOfDense(denseIndex);
        while (cursor.next()) {
            out[n++] = cursor.neighborDenseIndex();
        }
        return n;
    }

    /** Upper bound on neighbors per node (six cardinal directions). */
    int MAX_NEIGHBORS = 6;

    int nodeCount();

    /**
     * One past the highest dense index ever handed out (the size a
     * parallel per-dense-index array must have). Default is a conservative
     * {@link #nodeCount()} for topologies that never reuse/free indices.
     */
    default int denseCapacity() {
        return nodeCount();
    }

    /**
     * Registers a listener notified on any topology mutation (add/remove/
     * update), BEFORE fusion or discrete-evaluation logic runs. Both
     * SegmentedGraphImpl and DiscreteGraphImpl attach here instead of each
     * re-detecting "what changed" independently.
     */
    void addTopologyListener(TopologyListener listener);
}
