package net.bichal.mods.celeris.graph.segmented;

import net.bichal.mods.celeris.core.topology.NodeId;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

final class GraphSegmentImpl<T> implements GraphSegment<T> {

    // Positions kept as packed longs; the List<NodeId> API view wraps them
    // and materialises a NodeId only when an element is actually read.
    private final long[] rawPositions;
    private final NodeId nodeA;
    private final NodeId nodeB;
    private final PositionsView positionsView = new PositionsView();
    private final long id;
    private T payload;

    /** Index in {@code SegmentedGraphImpl.allSegments} for O(1) swap-remove; owned by that class. */
    int listIndex = -1;

    /** True while the segment was created by the mutation in progress (see {@code SegmentedGraphImpl}). */
    boolean createdInCall;

    /** True while queued in {@code SegmentedGraphImpl}'s dirty queue. */
    boolean dirtyQueued;

    GraphSegmentImpl(long id, long[] rawPositions, int count, NodeId nodeA, NodeId nodeB, T payload) {
        this.id = id;
        this.rawPositions = java.util.Arrays.copyOf(rawPositions, count);
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.payload = payload;
    }

    @Override
    public List<NodeId> positions() {
        return positionsView;
    }

    /** Packed position at {@code i}; allocation-free alternative to {@code positions().get(i).raw()}. */
    @Override
    public long rawPositionAt(int i) {
        return rawPositions[i];
    }

    @Override
    public int positionCount() {
        return rawPositions.length;
    }

    @Override
    public long id() {
        return id;
    }

    static final int RUN_DIFFERENT = 0;
    static final int RUN_SAME = 1;
    static final int RUN_REVERSED = 2;

    /** Whether {@code path[0..count)} is this run in the same orientation, the opposite one, or a different run. */
    int compareRun(long[] path, int count) {
        long[] mine = rawPositions;
        if (mine.length != count) return RUN_DIFFERENT;
        boolean forward = true;
        boolean backward = true;
        for (int i = 0; i < count && (forward || backward); i++) {
            forward &= mine[i] == path[i];
            backward &= mine[i] == path[count - 1 - i];
        }
        return forward ? RUN_SAME : backward ? RUN_REVERSED : RUN_DIFFERENT;
    }

    @Override
    public int length() {
        return rawPositions.length - 1;
    }

    @Override
    public NodeId nodeA() {
        return nodeA;
    }

    @Override
    public NodeId nodeB() {
        return nodeB;
    }

    @Override
    public T payload() {
        return payload;
    }

    @Override
    public void setPayload(T payload) {
        this.payload = payload;
    }

    NodeId otherEnd(NodeId end) {
        if (end.equals(nodeA)) return nodeB;
        if (end.equals(nodeB)) return nodeA;
        throw new IllegalArgumentException("Position is not an endpoint of this segment");
    }

    private final class PositionsView extends AbstractList<NodeId> implements RandomAccess {
        @Override
        public NodeId get(int index) {
            return new NodeId(rawPositions[index]);
        }

        @Override
        public int size() {
            return rawPositions.length;
        }
    }
}
