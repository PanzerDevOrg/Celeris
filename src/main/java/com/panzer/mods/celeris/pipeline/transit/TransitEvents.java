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
    /** The item could not stay in the network (see {@code TransitMigrator.LostSink}); the segment id is {@code GraphSegment.NO_ID}. */
    int LEFT_LOST = 2;

    /**
     * An item started travelling on a segment, {@code progress} blocks from the entry end. When an item moves
     * from one segment to another, this is reported first for the new segment and then {@link #itemLeft}
     * with {@link #LEFT_TRANSFERRED} for the old one, both with the same handle and tick.
     */
    default void itemEntered(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
    }

    /** An item stopped being on this segment's queue: handed to an endpoint or moved to another segment. */
    default void itemLeft(long segmentId, boolean forward, int handle, int reason, long tick) {
    }

    /** An item's speed was changed with {@code TransitSolver.setItemSpeed}; {@code progress} is where it was at that moment. */
    default void itemSpeedChanged(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
    }

    /** The head of a queue started (or stopped) waiting at the exit; the whole queue stops behind it. */
    default void queueBlocked(long segmentId, boolean forward, boolean blocked, long tick) {
    }
}
