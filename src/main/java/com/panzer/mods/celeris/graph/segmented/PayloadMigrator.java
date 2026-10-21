package com.panzer.mods.celeris.graph.segmented;

import java.util.List;

/**
 * Optional hook that carries payload state across graph mutations. Without
 * one, every segment the graph creates starts with a fresh payload from the
 * {@code emptyPayload} supplier, so state held by segments that were split,
 * merged or rebuilt is lost.
 *
 * <p>With a migrator, {@link SegmentedGraphImpl} calls {@link #migrate} once
 * at the end of each mutating call ({@code addNode}, {@code removeNode},
 * {@code updateConnections}), after the graph is fully consistent and before
 * the call returns. {@code removed} holds the segments that no longer exist
 * (their payloads are intact) and {@code created} the new ones (holding fresh
 * payloads). Move or merge state from the first into the second with
 * {@link GraphSegment#setPayload} or by mutating the new payloads.
 *
 * <p>A graph with a migrator keeps a segment untouched, with its id and
 * payload, when a mutation only rebuilds it to the same shape: it appears in
 * neither list. A segment created and dropped within the same call appears in
 * neither list either. Only genuine changes are reported.
 *
 * <p>Both lists are reused between calls: do not keep them (or iterate them
 * after returning). The migrator must not mutate the graph. Runs on the
 * thread that mutates the graph.
 */
@FunctionalInterface
public interface PayloadMigrator<T> {

    void migrate(List<GraphSegment<T>> removed, List<GraphSegment<T>> created);
}
