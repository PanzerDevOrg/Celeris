package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.core.topology.NodeId;

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
    private volatile T payload;

    /** Index in {@code SegmentedGraphImpl.allSegments} for O(1) swap-remove; owned by that class. */
    int listIndex = -1;

    GraphSegmentImpl(long[] rawPositions, int count, NodeId nodeA, NodeId nodeB, T payload) {
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
    long rawPositionAt(int i) {
        return rawPositions[i];
    }

    int positionCount() {
        return rawPositions.length;
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

    void setPayload(T payload) {
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
