package com.panzer.mods.celeris.core.dirty;

/**
 * A deduplicated queue of dense node indices awaiting re-evaluation.
 * Shared by graph/discrete (every signal-level change enqueues here) and,
 * optionally, pipeline/continuous as a future optimization (skip ticking a
 * segment with no in-flight payload). Deliberately index-based ({@code
 * int}), not NodeId-based, so consumers already holding a dense index (the
 * common case, resolved once via {@code NetworkTopology#denseIndexOf})
 * never pay a second lookup to enqueue.
 */
public interface DirtyQueue {

    /** No-op if the index is already pending -- callers never need to check first. */
    void markDirty(int denseIndex);

    /** {@code -1} if empty. FIFO within a tick is NOT guaranteed -- see LongRingDirtyQueue javadoc. */
    int poll();

    boolean isIdle();

    int pendingCount();
}
