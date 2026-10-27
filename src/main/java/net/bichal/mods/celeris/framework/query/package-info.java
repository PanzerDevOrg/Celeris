/**
 * Throttled caching for expensive per-tick queries.
 *
 * <p>{@link net.bichal.mods.celeris.framework.query.ThrottledQueryCache} --
 * caches a query's result for a fixed number of ticks per key, so you can
 * call it every tick without re-running the underlying query every tick.
 *
 * @since 0.1.0
 */
package net.bichal.mods.celeris.framework.query;
