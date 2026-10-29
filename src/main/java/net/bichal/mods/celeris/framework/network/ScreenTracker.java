package net.bichal.mods.celeris.framework.network;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts how many players currently have a menu/screen open for a given
 * block position, so a block entity can skip expensive per-tick work (sync
 * packets, live recalculation) when nobody's actually looking at it.
 *
 * <p>Thread-safe -- {@link #onScreenOpened}/{@link #onScreenClosed} can be
 * called from any thread that handles menu open/close events.
 */
public final class ScreenTracker {

    private final ConcurrentHashMap<Long, Integer> viewerCounts;

    public ScreenTracker() {
        this.viewerCounts = new ConcurrentHashMap<>();
    }

    public void onScreenOpened(long packedPos) {
        viewerCounts.merge(packedPos, 1, Integer::sum);
    }

    public void onScreenClosed(long packedPos) {
        // One atomic map operation: the decrement and the removal cannot interleave with an open.
        viewerCounts.computeIfPresent(packedPos, (ignoredKey, count) -> count <= 1 ? null : count - 1);
    }

    /** Whether at least one player currently has a screen open for {@code packedPos}. */
    public boolean hasViewers(long packedPos) {
        Integer count = viewerCounts.get(packedPos);
        return count != null && count > 0;
    }
}
