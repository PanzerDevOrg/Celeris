package net.bichal.mods.celeris.graph.segmented;

import net.bichal.mods.celeris.core.topology.ConnectionMask;
import net.bichal.mods.celeris.core.topology.NodeId;

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
 *
 * <h2>Threading</h2>
 * A graph, its segments and their payloads belong to one thread, normally the
 * server thread: every method here, including the reads, must be called from
 * it. Nothing is synchronised. To work on another thread, copy the data into
 * flat arrays on the owner thread, process them elsewhere and hand the result
 * back (for example through {@code AsyncResultQueue}), applying it on the
 * owner thread.
 */
public interface SegmentedGraph<T> {

    /** Registers a position as a node. Re-fuses only the affected neighborhood. */
    void addNode(NodeId id, ConnectionMask connections);

    /**
     * Registers or updates many nodes at once ({@code rawIds[i]} is a packed
     * {@link NodeId#raw()}, {@code connectionBits[i]} its {@link ConnectionMask#bits()}),
     * the first {@code count} entries of each array. The result equals calling
     * {@link #addNode} for each entry in order, but the fusion runs once for the
     * whole batch: use it to load a chunk or a saved network, where adding node
     * by node re-traces every growing run on each step. The default
     * implementation simply loops.
     */
    default void addNodes(long[] rawIds, int[] connectionBits, int count) {
        for (int i = 0; i < count; i++) {
            addNode(new NodeId(rawIds[i]), ConnectionMask.of(connectionBits[i]));
        }
    }

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

    /** Receives one segment and the direction index it leaves a node in. */
    @FunctionalInterface
    interface SegmentVisitor<T> {
        void visit(GraphSegment<T> segment, int direction);
    }

    /**
     * Allocation-free {@link #branchesAt}: calls {@code visitor} once per
     * segment touching {@code id}, with the direction it leaves in, in
     * ascending direction order. Do not mutate the graph from the visitor.
     */
    default void forEachBranchAt(NodeId id, SegmentVisitor<T> visitor) {
        branchesAt(id).forEach(visitor::visit);
    }

    /** The segment leaving {@code id} in {@code direction}, or null. Allocation-free. */
    default GraphSegment<T> branchAt(NodeId id, int direction) {
        for (var e : branchesAt(id).entrySet()) {
            if (e.getValue() == direction) return e.getKey();
        }
        return null;
    }

    /**
     * Allocation-free {@link #neighbors}: calls {@code visitor} once per
     * segment sharing an endpoint with {@code segment}, with the direction it
     * leaves that endpoint in. Do not mutate the graph from the visitor.
     */
    default void forEachNeighbor(GraphSegment<T> segment, SegmentVisitor<T> visitor) {
        neighbors(segment).forEach(visitor::visit);
    }

    /**
     * Counter that changes whenever the set of segments changes (a segment is
     * created or removed). Lets a consumer cache something derived from the
     * segment list, such as a sorted copy, and rebuild it only when this value
     * differs from the one it saw. Graphs that cannot tell return a value that
     * always differs, which makes such caches rebuild every time (correct, slower).
     */
    default long structureVersion() {
        return System.nanoTime();
    }

    /** Number of segments; same as {@code segments().size()}. */
    default int segmentCount() {
        return segments().size();
    }

    /**
     * The segment at {@code index} of the {@link #segments()} order, for
     * indexed loops that must not allocate an iterator. The order is stable
     * while the graph is not mutated. The default implementation walks the
     * collection (O(index)); {@link SegmentedGraphImpl} overrides it in O(1).
     */
    default GraphSegment<T> segmentByIndex(int index) {
        int i = 0;
        for (GraphSegment<T> segment : segments()) {
            if (i++ == index) return segment;
        }
        throw new IndexOutOfBoundsException(index);
    }

    /**
     * Registers the single listener told about structural changes, or
     * {@code null} to remove it. A graph with a listener keeps segments that
     * are rebuilt to an unchanged shape (see {@link PayloadMigrator}), so it
     * only reports real changes.
     */
    default void setListener(SegmentGraphListener<T> listener) {
        throw new UnsupportedOperationException("This graph does not support listeners");
    }

    /**
     * Queues {@code segment} to be reported to {@link SegmentGraphListener#payloadDirty}
     * at the next {@link #flushDirty()}. Marking a segment twice before a flush
     * reports it once. Allocation-free once the queue has grown.
     */
    default void markPayloadDirty(GraphSegment<T> segment) {
        throw new UnsupportedOperationException("This graph does not support dirty tracking");
    }

    /**
     * Reports every segment marked since the last flush that still exists, then
     * clears the queue. Call it once per tick. Returns how many were reported.
     */
    default int flushDirty() {
        return 0;
    }
}
