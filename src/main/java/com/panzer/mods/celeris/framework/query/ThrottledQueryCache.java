package com.panzer.mods.celeris.framework.query;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Caches the result of an expensive, per-key query for a fixed number of
 * ticks, so a block entity can call {@link #getOrQuery} every tick without
 * actually re-running the query every tick (e.g. scanning neighbors,
 * gathering nearby entities).
 *
 * @param <T> the element type of a query result
 */
public final class ThrottledQueryCache<T> {

    private final ConcurrentHashMap<Long, Entry<T>> entries;
    private final int throttleTicks;

    /** @param throttleTicks minimum ticks between re-running the query for the same key; must be positive */
    public ThrottledQueryCache(int throttleTicks) {
        if (throttleTicks <= 0) {
            throw new IllegalArgumentException("Celeris query throttle must be positive");
        }
        this.throttleTicks = throttleTicks;
        this.entries = new ConcurrentHashMap<>();
    }

    /** Returns the cached result for {@code key} if it's still fresh, otherwise runs {@code query} and caches the result. */
    public List<T> getOrQuery(long key, long currentGameTime, Supplier<List<T>> query) {
        Entry<T> existing = entries.get(key);
        if (existing != null && currentGameTime - existing.lastQueriedTick < throttleTicks) {
            return existing.result;
        }

        List<T> fresh = query.get();
        entries.put(key, new Entry<>(fresh, currentGameTime));
        return fresh;
    }

    /** Forces the next {@link #getOrQuery} call for {@code key} to re-run the query. */
    public void invalidate(long key) {
        entries.remove(key);
    }

    public void invalidateAll() {
        entries.clear();
    }

    private record Entry<T>(List<T> result, long lastQueriedTick) {
    }
}
