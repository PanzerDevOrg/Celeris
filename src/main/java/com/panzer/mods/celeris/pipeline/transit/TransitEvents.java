package com.panzer.mods.celeris.pipeline.transit;

/**
 * Optional stream of what happens to items, for consumers that mirror the
 * simulation elsewhere (for instance a client renderer). Called from
 * {@link TransitSolver#tick()} and {@link TransitSolver#insert}, on the graph's
 * thread, without allocation on the solver side.
 */
public interface TransitEvents {

    int LEFT_DELIVERED = 0;
    int LEFT_TRANSFERRED = 1;

    /** An item started travelling on a segment, {@code progress} blocks from the entry end. */
    default void itemEntered(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
    }

    /** An item stopped being on this segment's queue: handed to an endpoint or moved to another segment. */
    default void itemLeft(long segmentId, boolean forward, int handle, int reason, long tick) {
    }

    /** The head of a queue started (or stopped) waiting at the exit; the whole queue stops behind it. */
    default void queueBlocked(long segmentId, boolean forward, boolean blocked, long tick) {
    }
}
