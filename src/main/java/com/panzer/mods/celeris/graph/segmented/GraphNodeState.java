package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.util.math.BranchlessTables;

/**
 * Fusion-logic classification over a raw 6-bit connection mask: everything
 * about "which directions are connected" lives in {@code core.topology};
 * this adds only the straight-run/junction predicates specific to the
 * segmented pipeline's fusion strategy. Pure static functions on an
 * {@code int} -- no per-node object, no boxed iteration (the previous
 * {@code Iterable<Integer> connections()} allocated an iterator plus an
 * {@code Integer} per direction on every trace step).
 */
final class GraphNodeState {

    private GraphNodeState() {
    }

    static boolean isJunction(int maskBits) {
        return !isStraightThrough(maskBits);
    }

    static boolean isStraightThrough(int maskBits) {
        if (Integer.bitCount(maskBits) != 2) {
            return false;
        }
        int first = Integer.numberOfTrailingZeros(maskBits);
        int second = 31 - Integer.numberOfLeadingZeros(maskBits);
        return BranchlessTables.opposite(first) == second;
    }

    static boolean has(int maskBits, int directionIndex) {
        return (maskBits & (1 << directionIndex)) != 0;
    }

    /**
     * The single connection direction other than {@code incoming}, or
     * {@code -1} if there isn't exactly one such direction (used while
     * tracing a straight-through node: clear the incoming bit and read off
     * whatever single bit remains).
     */
    static int outgoingOtherThan(int maskBits, int incoming) {
        int remaining = maskBits & ~(1 << incoming);
        if (Integer.bitCount(remaining) != 1) {
            return -1;
        }
        return Integer.numberOfTrailingZeros(remaining);
    }
}
