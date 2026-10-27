package net.bichal.mods.celeris.pipeline.transit;

/**
 * The payload of a segment in a transit network: one {@link TransitQueue} per
 * travel direction. {@code forward} runs from {@code nodeA} to {@code nodeB}.
 * Use {@code TransitPayload::new} as the graph's empty-payload supplier.
 */
public final class TransitPayload {

    private final TransitQueue forward = new TransitQueue();
    private final TransitQueue backward = new TransitQueue();

    /** Items moving from {@code nodeA} to {@code nodeB}. */
    public TransitQueue forward() {
        return forward;
    }

    /** Items moving from {@code nodeB} to {@code nodeA}. */
    public TransitQueue backward() {
        return backward;
    }

    public boolean isEmpty() {
        return forward.isEmpty() && backward.isEmpty();
    }
}
