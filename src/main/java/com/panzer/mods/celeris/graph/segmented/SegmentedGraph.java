package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NodeId;

import java.util.Collection;
import java.util.Map;

/**
 * Network graph that fuses straight runs into segments (the "segmented"
 * paradigm, as opposed to {@code graph.discrete}, which evaluates every node
 * individually). Used for pipe- and wire-style networks.
 *
 * <p>A "node" is any NodeId the consumer registers as part of the network
 * (an endpoint, junction, or corner). Straight runs of connected positions
 * between two such nodes collapse into a single {@link GraphSegment}, so
 * the tick/route cost is O(segments), not O(blocks).
 *
 * <p>Segment fusion/splitting is fully automatic and localized to the
 * touched neighborhood: every mutating call below leaves the graph in a
 * consistent, already-fused state before returning. Consumers never call a
 * separate "recompute" step, and never observe a half-fused intermediate
 * graph.
 */
public interface SegmentedGraph<T> {

    /** Registers a position as a node. Re-fuses only the affected neighborhood. */
    void addNode(NodeId id, ConnectionMask connections);

    /** Removes a node. Re-fuses only the segments that touched it. */
    void removeNode(NodeId id);

    /** Updates a node's connection mask. Re-fuses only the affected neighborhood. */
    void updateConnections(NodeId id, ConnectionMask connections);

    /**
     * All fused segments currently in this network. A live, unmodifiable,
     * duplicate-free view (not a snapshot): iterate it freely on the tick
     * path without copying, but do not mutate the graph mid-iteration.
     */
    Collection<GraphSegment<T>> segments();

    /** The segment containing the given position, if any. */
    GraphSegment<T> segmentAt(NodeId id);

    /** Neighboring segments reachable from a given segment's endpoints, keyed by direction index. */
    Map<GraphSegment<T>, Integer> neighbors(GraphSegment<T> segment);

    /** All branches touching id, keyed by the direction each leads off in. */
    Map<GraphSegment<T>, Integer> branchesAt(NodeId id);
}
