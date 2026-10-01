package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopology;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.util.math.BranchlessTables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Default {@link SegmentedGraph}: fuses straight runs of nodes into segments
 * (segmentByInteriorPos, segmentsByEndpointDir, rebuildAround,
 * findNearestJunction) on top of an injected {@link NetworkTopology}, which
 * owns the raw adjacency (which NodeId connects to which).
 *
 * <h2>Storage</h2>
 * <ul>
 *   <li>Keys are packed {@code NodeId.raw()} longs in fastutil maps -- no
 *       record hashing, no boxed {@code Integer} direction keys.</li>
 *   <li>Endpoint -> segment is a fixed {@code GraphSegmentImpl[6]} per
 *       endpoint (index = direction), replacing a {@code Map<Integer, _>}.</li>
 *   <li>{@link #segments()} is a live unmodifiable view over an
 *       {@code ArrayList}; segments store their index for O(1) swap-remove.
 *       No per-call copy (the pipeline calls this every tick).</li>
 * </ul>
 * Mutations are single-threaded (server thread), matching NetworkTopologyImpl.
 */
public final class SegmentedGraphImpl<T> implements SegmentedGraph<T> {

    private static final int DIRS = NetworkTopology.MAX_NEIGHBORS;

    private final NetworkTopology topology;
    private final Long2ObjectOpenHashMap<GraphSegmentImpl<T>[]> segmentsByEndpointDir = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<GraphSegmentImpl<T>> segmentByInteriorPos = new Long2ObjectOpenHashMap<>();
    private final ArrayList<GraphSegmentImpl<T>> allSegments = new ArrayList<>();
    private final Collection<GraphSegment<T>> segmentsView = Collections.unmodifiableList(allSegments);
    private final Supplier<T> emptyPayload;

    // Trace scratch: a straight run can be arbitrarily long, so this grows
    // geometrically and is reused across traces (zero steady-state allocation).
    private long[] pathScratch = new long[32];

    public SegmentedGraphImpl(NetworkTopology topology, Supplier<T> emptyPayload) {
        this.topology = topology;
        this.emptyPayload = emptyPayload;
    }

    @Override
    public void addNode(NodeId id, ConnectionMask connections) {
        GraphSegmentImpl<T> splitTarget = segmentByInteriorPos.get(id.raw());
        if (splitTarget != null) {
            unregisterSegment(splitTarget);
        }

        topology.addNode(id, connections);
        rebuildAround(id);

        if (splitTarget != null && !GraphNodeState.isJunction(connections.bits())) {
            rebuildAround(splitTarget.nodeA());
            rebuildAround(splitTarget.nodeB());
        }
    }

    @Override
    public void removeNode(NodeId id) {
        if (topology.denseIndexOf(id) < 0) {
            return;
        }
        int removedBits = topology.connectionsOf(id).bits();
        topology.removeNode(id);
        removeSegmentsTouching(id.raw());
        for (int bits = removedBits; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            long nearestJunction = findNearestJunction(id.raw(), dir);
            if (nearestJunction != NONE) {
                rebuildAround(nearestJunction);
            }
        }
    }

    @Override
    public void updateConnections(NodeId id, ConnectionMask connections) {
        boolean existed = topology.denseIndexOf(id) >= 0;
        int previousBits = existed ? topology.connectionsOf(id).bits() : 0;
        topology.updateConnections(id, connections);
        rebuildAround(id.raw());

        for (int bits = previousBits; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            long nearestJunction = findNearestJunction(id.raw(), dir);
            if (nearestJunction != NONE) {
                rebuildAround(nearestJunction);
            }
        }
    }

    // Sentinel for "no node" on packed-long paths. NodeId.pack never yields
    // all-ones for in-range coordinates (y is 12-bit sign-extended: -2048..2047,
    // x/z 26-bit), so this value cannot collide with a real id.
    private static final long NONE = -1L;

    private long findNearestJunction(long fromRaw, int dir) {
        long current = fromRaw;
        int travelDir = dir;
        while (true) {
            current = NodeId.stepRaw(current, travelDir);
            int idx = topology.denseIndexOf(new NodeId(current));
            if (idx < 0) {
                return NONE;
            }
            int bits = topology.connectionsOf(new NodeId(current)).bits();
            if (GraphNodeState.isJunction(bits)) {
                return current;
            }
            int outgoing = GraphNodeState.outgoingOtherThan(bits, BranchlessTables.opposite(travelDir));
            if (outgoing == -1) {
                return NONE;
            }
            travelDir = outgoing;
        }
    }

    /** Live, unmodifiable view -- never copied. Do not mutate the graph while iterating it. */
    @Override
    public Collection<GraphSegment<T>> segments() {
        return segmentsView;
    }

    /**
     * Interior positions map 1:1 to a single segment, so that lookup is the
     * hot-path fast case. A pos that is itself a node (endpoint/junction)
     * is never in segmentByInteriorPos -- registerSegment only indexes
     * strictly interior path positions -- so it must fall back to
     * segmentsByEndpointDir. A junction touches multiple segments; any one
     * of them is a valid answer here.
     */
    @Override
    public GraphSegment<T> segmentAt(NodeId id) {
        GraphSegmentImpl<T> interior = segmentByInteriorPos.get(id.raw());
        if (interior != null) {
            return interior;
        }
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(id.raw());
        if (byDir == null) {
            return null;
        }
        for (int d = 0; d < DIRS; d++) {
            if (byDir[d] != null) {
                return byDir[d];
            }
        }
        return null;
    }

    @Override
    public Map<GraphSegment<T>, Integer> branchesAt(NodeId id) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(id.raw());
        if (byDir == null) {
            return Map.of();
        }
        Map<GraphSegment<T>, Integer> result = new HashMap<>(8);
        for (int d = 0; d < DIRS; d++) {
            if (byDir[d] != null) {
                result.put(byDir[d], d);
            }
        }
        return result;
    }

    @Override
    public Map<GraphSegment<T>, Integer> neighbors(GraphSegment<T> segment) {
        Map<GraphSegment<T>, Integer> result = new HashMap<>(12);
        collectNeighbor(segment.nodeA().raw(), segment, result);
        collectNeighbor(segment.nodeB().raw(), segment, result);
        return result;
    }

    private void collectNeighbor(long endpointRaw, GraphSegment<T> self, Map<GraphSegment<T>, Integer> out) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(endpointRaw);
        if (byDir == null) {
            return;
        }
        for (int d = 0; d < DIRS; d++) {
            GraphSegmentImpl<T> other = byDir[d];
            if (other != null && other != self) {
                out.put(other, d);
            }
        }
    }

    private void removeSegmentsTouching(long idRaw) {
        // Every segment touching idRaw is indexed under it as an endpoint;
        // walk that array instead of scanning allSegments.
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(idRaw);
        if (byDir == null) {
            return;
        }
        for (int d = 0; d < DIRS; d++) {
            GraphSegmentImpl<T> s = byDir[d];
            if (s != null) {
                unregisterSegment(s);
            }
        }
    }

    private void unregisterSegment(GraphSegmentImpl<T> segment) {
        removeFromAll(segment);
        removeEndpointEntry(segment.nodeA().raw(), segment);
        removeEndpointEntry(segment.nodeB().raw(), segment);
        int n = segment.positionCount();
        for (int i = 1; i < n - 1; i++) {
            long p = segment.rawPositionAt(i);
            if (segmentByInteriorPos.get(p) == segment) {
                segmentByInteriorPos.remove(p);
            }
        }
    }

    private void removeFromAll(GraphSegmentImpl<T> segment) {
        int idx = segment.listIndex;
        if (idx < 0) {
            return;
        }
        int lastIdx = allSegments.size() - 1;
        GraphSegmentImpl<T> last = allSegments.remove(lastIdx);
        if (last != segment) {
            allSegments.set(idx, last);
            last.listIndex = idx;
        }
        segment.listIndex = -1;
    }

    private void removeEndpointEntry(long endpointRaw, GraphSegmentImpl<T> segment) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(endpointRaw);
        if (byDir == null) {
            return;
        }
        boolean any = false;
        for (int d = 0; d < DIRS; d++) {
            if (byDir[d] == segment) {
                byDir[d] = null;
            } else if (byDir[d] != null) {
                any = true;
            }
        }
        if (!any) {
            segmentsByEndpointDir.remove(endpointRaw);
        }
    }

    private void rebuildAround(NodeId id) {
        rebuildAround(id.raw());
    }

    private void rebuildAround(long idRaw) {
        NodeId id = new NodeId(idRaw);
        if (topology.denseIndexOf(id) < 0) {
            return;
        }
        int bits = topology.connectionsOf(id).bits();
        removeSegmentsTouching(idRaw);
        if (!GraphNodeState.isJunction(bits)) {
            return;
        }
        for (int rem = bits; rem != 0; rem &= rem - 1) {
            traceSegment(id, Integer.numberOfTrailingZeros(rem));
        }
    }

    private void traceSegment(NodeId start, int firstStep) {
        GraphSegmentImpl<T>[] startEndpoint = segmentsByEndpointDir.get(start.raw());
        if (startEndpoint != null && startEndpoint[firstStep] != null) {
            return;
        }

        long[] path = pathScratch;
        int count = 0;
        path[count++] = start.raw();

        long current = start.raw();
        int travelDir = firstStep;

        while (true) {
            long next = NodeId.stepRaw(current, travelDir);
            if (count == path.length) {
                path = pathScratch = java.util.Arrays.copyOf(path, path.length << 1);
            }
            path[count++] = next;

            NodeId nextId = new NodeId(next);
            int incoming = BranchlessTables.opposite(travelDir);
            if (topology.denseIndexOf(nextId) < 0) {
                return;
            }
            int nextBits = topology.connectionsOf(nextId).bits();
            if (!GraphNodeState.has(nextBits, incoming)) {
                // A node exists there but does not itself list a connection
                // back toward where we came from -- the link isn't
                // reciprocal, so there is no real pipe continuing through it.
                return;
            }

            if (GraphNodeState.isJunction(nextBits)) {
                registerSegment(path, count, start, firstStep, nextId, incoming);
                return;
            }

            int outgoing = GraphNodeState.outgoingOtherThan(nextBits, incoming);
            if (outgoing == -1) {
                return;
            }

            current = next;
            travelDir = outgoing;
        }
    }

    @SuppressWarnings("unchecked")
    private GraphSegmentImpl<T>[] endpointSlots(long endpointRaw) {
        GraphSegmentImpl<T>[] slots = segmentsByEndpointDir.get(endpointRaw);
        if (slots == null) {
            slots = (GraphSegmentImpl<T>[]) new GraphSegmentImpl[DIRS];
            segmentsByEndpointDir.put(endpointRaw, slots);
        }
        return slots;
    }

    private void registerSegment(long[] path, int count, NodeId nodeA, int dirFromA, NodeId nodeB, int dirFromB) {
        GraphSegmentImpl<T> segment = new GraphSegmentImpl<>(path, count, nodeA, nodeB, emptyPayload.get());
        segment.listIndex = allSegments.size();
        allSegments.add(segment);
        endpointSlots(nodeA.raw())[dirFromA] = segment;
        endpointSlots(nodeB.raw())[dirFromB] = segment;
        for (int i = 1; i < count - 1; i++) {
            segmentByInteriorPos.put(path[i], segment);
        }
    }
}
