package net.bichal.mods.celeris.graph.segmented;

import net.bichal.mods.celeris.core.topology.ConnectionMask;
import net.bichal.mods.celeris.core.topology.NetworkTopology;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.util.math.BranchlessTables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    private final PayloadMigrator<T> migrator;
    private SegmentGraphListener<T> listener;
    private GraphSegmentImpl<T>[] dirtyQueue = newSegmentArray(16);
    private int dirtyCount;

    // Change tracking, active only when a migrator is set: segments dropped
    // and created by the mutation in progress, so the migrator sees the net
    // difference. A rebuilt segment of unchanged shape is relinked, not replaced.
    private final ArrayList<GraphSegmentImpl<T>> removedInCall = new ArrayList<>();
    private final ArrayList<GraphSegmentImpl<T>> createdInCall = new ArrayList<>();
    private final List<GraphSegment<T>> removedView = Collections.unmodifiableList(removedInCall);
    private final List<GraphSegment<T>> createdView = Collections.unmodifiableList(createdInCall);
    private boolean migrating;
    private long nextSegmentId;
    private long structureVersion;

    // Trace scratch: a straight run can be arbitrarily long, so this grows
    // geometrically and is reused across traces (zero steady-state allocation).
    private long[] pathScratch = new long[32];

    public SegmentedGraphImpl(NetworkTopology topology, Supplier<T> emptyPayload) {
        this(topology, emptyPayload, null);
    }

    /**
     * @param migrator optional; carries payload state across splits and merges.
     *                 With it, segments rebuilt to an unchanged shape keep
     *                 their id and payload. See {@link PayloadMigrator}.
     */
    public SegmentedGraphImpl(NetworkTopology topology, Supplier<T> emptyPayload, PayloadMigrator<T> migrator) {
        this.topology = topology;
        this.emptyPayload = emptyPayload;
        this.migrator = migrator;
    }

    @SuppressWarnings("unchecked")
    private static <T> GraphSegmentImpl<T>[] newSegmentArray(int n) {
        return (GraphSegmentImpl<T>[]) new GraphSegmentImpl[n];
    }

    private boolean tracking() {
        return migrator != null || listener != null;
    }

    @Override
    public void setListener(SegmentGraphListener<T> listener) {
        beginMutation();
        this.listener = listener;
    }

    @Override
    public void markPayloadDirty(GraphSegment<T> segment) {
        GraphSegmentImpl<T> impl = (GraphSegmentImpl<T>) segment;
        if (impl.dirtyQueued || impl.listIndex < 0) {
            return;
        }
        impl.dirtyQueued = true;
        if (dirtyCount == dirtyQueue.length) {
            dirtyQueue = java.util.Arrays.copyOf(dirtyQueue, dirtyCount << 1);
        }
        dirtyQueue[dirtyCount++] = impl;
    }

    @Override
    public int flushDirty() {
        int reported = 0;
        // The listener may mark again while we drain; those wait for the next flush.
        int n = dirtyCount;
        GraphSegmentImpl<T>[] reportBatch = dirtyQueue;
        for (int i = 0; i < n; i++) {
            GraphSegmentImpl<T> s = reportBatch[i];
            s.dirtyQueued = false;
            if (s.listIndex >= 0 && listener != null) {
                reported++;
                listener.payloadDirty(s);
            }
        }
        // A listener that marked again may have grown (replaced) the queue: compact the current array.
        GraphSegmentImpl<T>[] batch = dirtyQueue;
        if (dirtyCount == n) {
            java.util.Arrays.fill(batch, 0, n, null);
            dirtyCount = 0;
        } else {
            System.arraycopy(batch, n, batch, 0, dirtyCount - n);
            java.util.Arrays.fill(batch, dirtyCount - n, dirtyCount, null);
            dirtyCount -= n;
        }
        return reported;
    }

    @Override
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public int segmentCount() {
        return allSegments.size();
    }

    @Override
    public GraphSegment<T> segmentByIndex(int index) {
        return allSegments.get(index);
    }

    @Override
    public GraphSegment<T> branchAt(NodeId id, int direction) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(id.raw());
        return byDir == null ? null : byDir[direction];
    }

    @Override
    public void forEachBranchAt(NodeId id, SegmentVisitor<T> visitor) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(id.raw());
        if (byDir == null) {
            return;
        }
        for (int d = 0; d < DIRS; d++) {
            if (byDir[d] != null) {
                visitor.visit(byDir[d], d);
            }
        }
    }

    @Override
    public void forEachNeighbor(GraphSegment<T> segment, SegmentVisitor<T> visitor) {
        visitNeighbors(segment.nodeA().raw(), segment, visitor);
        visitNeighbors(segment.nodeB().raw(), segment, visitor);
    }

    private void visitNeighbors(long endpointRaw, GraphSegment<T> self, SegmentVisitor<T> visitor) {
        GraphSegmentImpl<T>[] byDir = segmentsByEndpointDir.get(endpointRaw);
        if (byDir == null) {
            return;
        }
        for (int d = 0; d < DIRS; d++) {
            GraphSegmentImpl<T> other = byDir[d];
            if (other != null && other != self) {
                visitor.visit(other, d);
            }
        }
    }

    private void beginMutation() {
        if (migrating) {
            throw new IllegalStateException("The graph cannot be mutated from a PayloadMigrator");
        }
    }

    /** Hands the net change of the finished mutation to the migrator, then forgets it. */
    private void endMutation() {
        if (!tracking()) {
            return;
        }
        try {
            if (!removedInCall.isEmpty() || !createdInCall.isEmpty()) {
                structureVersion++;
                migrating = true;
                if (migrator != null) {
                    migrator.migrate(removedView, createdView);
                }
                if (listener != null) {
                    for (int i = 0, n = removedInCall.size(); i < n; i++) {
                        listener.segmentRemoved(removedInCall.get(i));
                    }
                    for (int i = 0, n = createdInCall.size(); i < n; i++) {
                        listener.segmentCreated(createdInCall.get(i));
                    }
                }
            }
        } finally {
            migrating = false;
            for (int i = 0, n = createdInCall.size(); i < n; i++) {
                createdInCall.get(i).createdInCall = false;
            }
            createdInCall.clear();
            removedInCall.clear();
        }
    }

    @Override
    public void addNode(NodeId id, ConnectionMask connections) {
        beginMutation();
        try {
            addNodeInternal(id, connections);
        } finally {
            endMutation();
        }
    }

    private void addNodeInternal(NodeId id, ConnectionMask connections) {
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
    public void addNodes(long[] rawIds, int[] connectionBits, int count) {
        if (count < 0 || count > rawIds.length || count > connectionBits.length) {
            throw new IllegalArgumentException("count " + count + " exceeds the arrays");
        }
        beginMutation();
        try {
            addNodesInternal(rawIds, connectionBits, count);
        } finally {
            endMutation();
        }
    }

    private void addNodesInternal(long[] rawIds, int[] connectionBits, int count) {
        // Junctions whose segments must be rebuilt, collected while the topology is brought up to date.
        LongOpenHashSet affected = new LongOpenHashSet();
        int[] previousBits = new int[count];
        for (int i = 0; i < count; i++) {
            NodeId id = new NodeId(rawIds[i]);
            GraphSegmentImpl<T> inside = segmentByInteriorPos.get(id.raw());
            if (inside != null) {
                unregisterSegment(inside);
                affected.add(inside.nodeA().raw());
                affected.add(inside.nodeB().raw());
            }
            // A node that was an endpoint may stop being one (or change which runs it ends).
            removeSegmentsTouching(id.raw());
            previousBits[i] = topology.denseIndexOf(id) >= 0 ? topology.connectionsOf(id).bits() : 0;
            topology.addNode(id, ConnectionMask.of(connectionBits[i]));
        }

        // A run is walked once however many of its nodes were added: positions already walked are not entered again.
        LongOpenHashSet walked = new LongOpenHashSet();
        for (int i = 0; i < count; i++) {
            long raw = rawIds[i];
            if (GraphNodeState.isJunction(connectionBits[i])) {
                affected.add(raw);
            } else {
                walked.add(raw);
                collectJunctions(raw, connectionBits[i], walked, affected);
            }
            collectJunctions(raw, previousBits[i], walked, affected);
        }

        long[] junctions = affected.toLongArray();
        java.util.Arrays.sort(junctions); // same result whatever the hash order
        for (long raw : junctions) {
            if (topology.denseIndexOf(new NodeId(raw)) >= 0) {
                removeSegmentsTouching(raw);
            }
        }
        for (long raw : junctions) {
            NodeId id = new NodeId(raw);
            if (topology.denseIndexOf(id) < 0) {
                continue;
            }
            int bits = topology.connectionsOf(id).bits();
            if (!GraphNodeState.isJunction(bits)) {
                continue;
            }
            for (int rem = bits; rem != 0; rem &= rem - 1) {
                traceSegment(id, Integer.numberOfTrailingZeros(rem));
            }
        }
    }

    /** Adds to {@code out} the junction at the end of each straight run leaving {@code fromRaw} through {@code bits}. */
    private void collectJunctions(long fromRaw, int bits, LongOpenHashSet walked, LongOpenHashSet out) {
        for (int rem = bits; rem != 0; rem &= rem - 1) {
            long current = fromRaw;
            int travelDir = Integer.numberOfTrailingZeros(rem);
            while (true) {
                current = NodeId.stepRaw(current, travelDir);
                NodeId currentId = new NodeId(current);
                if (topology.denseIndexOf(currentId) < 0) {
                    break;
                }
                int currentBits = topology.connectionsOf(currentId).bits();
                if (GraphNodeState.isJunction(currentBits)) {
                    out.add(current);
                    break;
                }
                if (!walked.add(current)) {
                    break; // an earlier walk already covered the rest of this run, in both directions
                }
                int outgoing = GraphNodeState.outgoingOtherThan(currentBits, BranchlessTables.opposite(travelDir));
                if (outgoing == -1) {
                    break;
                }
                travelDir = outgoing;
            }
        }
    }

    @Override
    public void removeNode(NodeId id) {
        beginMutation();
        try {
            removeNodeInternal(id);
        } finally {
            endMutation();
        }
    }

    private void removeNodeInternal(NodeId id) {
        if (topology.denseIndexOf(id) < 0) {
            return;
        }
        int removedBits = topology.connectionsOf(id).bits();
        topology.removeNode(id);
        removeSegmentsTouching(id.raw());
        for (int bits = removedBits; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            if (findNearestJunction(id.raw(), dir)) {
                rebuildAround(foundJunction);
            }
        }
    }

    @Override
    public void updateConnections(NodeId id, ConnectionMask connections) {
        beginMutation();
        try {
            updateConnectionsInternal(id, connections);
        } finally {
            endMutation();
        }
    }

    private void updateConnectionsInternal(NodeId id, ConnectionMask connections) {
        // Like addNode: a position strictly inside a segment is not an endpoint of
        // any segment, so the run containing it has to be dropped explicitly.
        GraphSegmentImpl<T> splitTarget = segmentByInteriorPos.get(id.raw());
        if (splitTarget != null) {
            unregisterSegment(splitTarget);
        }
        boolean existed = topology.denseIndexOf(id) >= 0;
        int previousBits = existed ? topology.connectionsOf(id).bits() : 0;
        topology.updateConnections(id, connections);
        rebuildAround(id.raw());
        if (splitTarget != null && !GraphNodeState.isJunction(connections.bits())) {
            rebuildAround(splitTarget.nodeA());
            rebuildAround(splitTarget.nodeB());
        }

        for (int bits = previousBits; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            if (findNearestJunction(id.raw(), dir)) {
                rebuildAround(foundJunction);
            }
        }
    }

    // Result slot of findNearestJunction. Every long is a valid packed position
    // (including all-ones, i.e. (-1,-1,-1)), so no value can serve as a sentinel.
    private long foundJunction;

    private boolean findNearestJunction(long fromRaw, int dir) {
        long current = fromRaw;
        int travelDir = dir;
        while (true) {
            current = NodeId.stepRaw(current, travelDir);
            int idx = topology.denseIndexOf(new NodeId(current));
            if (idx < 0) {
                return false;
            }
            int bits = topology.connectionsOf(new NodeId(current)).bits();
            if (GraphNodeState.isJunction(bits)) {
                foundJunction = current;
                return true;
            }
            int outgoing = GraphNodeState.outgoingOtherThan(bits, BranchlessTables.opposite(travelDir));
            if (outgoing == -1) {
                return false;
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
        if (!tracking()) {
            structureVersion++;
        }
        if (tracking()) {
            if (segment.createdInCall) {
                createdInCall.remove(segment);
                segment.createdInCall = false;
            } else {
                removedInCall.add(segment);
            }
        }
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
        if (tracking()) {
            for (int i = 0, n = removedInCall.size(); i < n; i++) {
                GraphSegmentImpl<T> old = removedInCall.get(i);
                int match = old.compareRun(path, count);
                if (match != GraphSegmentImpl.RUN_DIFFERENT) {
                    removedInCall.remove(i);
                    if (match == GraphSegmentImpl.RUN_SAME) {
                        linkSegment(old, dirFromA, dirFromB);
                    } else {
                        // Traced from the other end: keep the segment's original orientation.
                        linkSegment(old, dirFromB, dirFromA);
                    }
                    return;
                }
            }
        }
        GraphSegmentImpl<T> segment = new GraphSegmentImpl<>(nextSegmentId++, path, count, nodeA, nodeB, emptyPayload.get());
        if (tracking()) {
            segment.createdInCall = true;
            createdInCall.add(segment);
        }
        linkSegment(segment, dirFromA, dirFromB);
    }

    private void linkSegment(GraphSegmentImpl<T> segment, int dirAtA, int dirAtB) {
        if (!tracking()) {
            structureVersion++;
        }
        segment.listIndex = allSegments.size();
        allSegments.add(segment);
        endpointSlots(segment.nodeA().raw())[dirAtA] = segment;
        endpointSlots(segment.nodeB().raw())[dirAtB] = segment;
        for (int i = 1, n = segment.positionCount() - 1; i < n; i++) {
            segmentByInteriorPos.put(segment.rawPositionAt(i), segment);
        }
    }
}
