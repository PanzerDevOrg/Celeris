package com.panzer.mods.celeris.pipeline.transit;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Cycles through the candidate exits of each node: after an item left by
 * direction {@code d}, the next goes to the first candidate after {@code d},
 * wrapping around. Keeps one int per node that has routed something.
 */
public final class RoundRobinRouter implements JunctionRouter {

    private final Long2IntOpenHashMap last = new Long2IntOpenHashMap();

    public RoundRobinRouter() {
        last.defaultReturnValue(-1);
    }

    @Override
    public int route(long nodeRaw, int fromDir, int handle, int candidates) {
        int after = last.get(nodeRaw);
        int above = after < 0 ? candidates : candidates & -(1 << (after + 1));
        int pick = above != 0 ? above : candidates;
        return Integer.numberOfTrailingZeros(pick);
    }

    @Override
    public void committed(long nodeRaw, int direction, int handle) {
        last.put(nodeRaw, direction);
    }

    /** Drops the cursor of a node that no longer exists. */
    public void forget(long nodeRaw) {
        last.remove(nodeRaw);
    }
}
