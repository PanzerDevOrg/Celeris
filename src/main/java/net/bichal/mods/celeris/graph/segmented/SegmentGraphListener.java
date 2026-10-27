package net.bichal.mods.celeris.graph.segmented;

/**
 * Observes a {@link SegmentedGraph} so a consumer does not have to poll
 * {@code segments()} to find out what changed.
 *
 * <p>{@link #segmentRemoved} and {@link #segmentCreated} are called at the end
 * of each mutating call, after the {@link PayloadMigrator} (if any) ran and
 * with the graph consistent: first every removed segment, then every created
 * one. Only net changes are reported: a segment rebuilt to the same shape is
 * kept and not reported, and one created and dropped inside the same call is
 * never reported. {@link #payloadDirty} is called from
 * {@link SegmentedGraph#flushDirty()}.
 *
 * <p>Callbacks run on the graph's thread and must not mutate the graph.
 */
public interface SegmentGraphListener<T> {

    /** A segment now exists. */
    default void segmentCreated(GraphSegment<T> segment) {
    }

    /** A segment no longer exists; its payload is still readable. */
    default void segmentRemoved(GraphSegment<T> segment) {
    }

    /** The consumer marked this segment's payload as changed since the last flush. */
    default void payloadDirty(GraphSegment<T> segment) {
    }
}
