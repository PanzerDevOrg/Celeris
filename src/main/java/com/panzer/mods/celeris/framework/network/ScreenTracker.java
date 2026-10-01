package com.panzer.mods.celeris.framework.network;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts how many players currently have a menu/screen open for a given
 * block position, so a block entity can skip expensive per-tick work (sync
 * packets, live recalculation) when nobody's actually looking at it.
 *
 * <p>Thread-safe -- {@link #onScreenOpened}/{@link #onScreenClosed} can be
 * called from any thread that handles menu open/close events.
 */
public final class ScreenTracker {

    private final ConcurrentHashMap<Long, AtomicInteger> viewerCounts;

    public ScreenTracker() {
        this.viewerCounts = new ConcurrentHashMap<>();
    }

    public void onScreenOpened(long packedPos) {
        viewerCounts.computeIfAbsent(packedPos, ignoredKey -> new AtomicInteger()).incrementAndGet();
    }

    public void onScreenClosed(long packedPos) {
        viewerCounts.computeIfPresent(packedPos, (ignoredKey, count) -> {
            int remaining = count.decrementAndGet();
            return remaining <= 0 ? null : count;
        });
    }

    /** Whether at least one player currently has a screen open for {@code packedPos}. */
    public boolean hasViewers(long packedPos) {
        AtomicInteger count = viewerCounts.get(packedPos);
        return count != null && count.get() > 0;
    }
}
