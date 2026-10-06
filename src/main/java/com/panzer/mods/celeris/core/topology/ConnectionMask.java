package com.panzer.mods.celeris.core.topology;

/**
 * A node's physical connectivity as a 6-bit mask, one bit per direction
 * ordinal -- identical bit layout and reasoning to the segmented pipeline's
 * {@code GraphNodeState.connectionMask}, but lifted out of graph/segmented
 * so DiscreteGraph can use the exact same representation without depending
 * on segment-fusion logic (isJunction/isStraightThrough) it has no use for.
 *
 * <p>Unlike {@code graph.segmented.GraphNodeState}, this type carries NO
 * fusion semantics -- isJunction()/isStraightThrough() stay in
 * graph/segmented, which wraps a ConnectionMask instead of reimplementing
 * the bit ops. DiscreteGraph only ever needs "which directions", never "is
 * this a fusable straight-through" -- every discrete node is evaluated
 * individually regardless of its connection shape.
 */
public record ConnectionMask(int bits) {

    public static final ConnectionMask NONE = new ConnectionMask(0);

    public static ConnectionMask of(int bits) {
        return new ConnectionMask(bits & 0x3F); // 6 valid direction bits
    }

    public boolean has(int directionIndex) {
        return (bits & (1 << directionIndex)) != 0;
    }

    public int count() {
        return Integer.bitCount(bits);
    }

    public ConnectionMask with(int directionIndex) {
        return new ConnectionMask(bits | (1 << directionIndex));
    }

    public ConnectionMask without(int directionIndex) {
        return new ConnectionMask(bits & ~(1 << directionIndex));
    }

    /** Iterates set direction indices without allocation (int mask walk, same trick as GraphNodeState). */
    public int nextDirection(int afterMaskState) {
        int remaining = bits & ~afterMaskState;
        return remaining == 0 ? -1 : Integer.numberOfTrailingZeros(remaining);
    }
}
