package net.bichal.mods.celeris.graph.segmented;

import net.bichal.mods.celeris.core.topology.NodeId;

import java.util.List;

/**
 * A fused straight run between two nodes (junctions/endpoints/corners).
 * The consumer attaches payload-specific state via T (e.g. an item queue,
 * a fluid amount, an energy buffer) -- GraphSegment itself only tracks
 * shape and connectivity. No contract change from the pre-2.0 {@code
 * GraphSegment} beyond {@code BlockPos} -> {@link NodeId}.
 */
public interface GraphSegment<T> {

    /** Ordered list of every world position this segment physically spans. */
    List<NodeId> positions();

    /** Length in blocks -- how many ticks an item takes to cross, etc. is consumer logic. */
    int length();

    /** The two node positions this segment connects (junctions/endpoints). */
    NodeId nodeA();

    NodeId nodeB();

    /** Consumer-attached payload state (queue of items, fluid amount, energy buffer...). */
    T payload();

    /**
     * Stable identifier of this segment: unique within its graph, assigned in
     * increasing order at creation and never reused, so it is safe as a
     * network key. A segment the graph keeps across a mutation (see
     * {@link PayloadMigrator}) keeps its id; a rebuilt one gets a new id.
     * Implementations that do not track ids return {@link #NO_ID}.
     */
    default long id() {
        return NO_ID;
    }

    /** Value of {@link #id()} for a segment without a stable id. */
    long NO_ID = -1L;

    /**
     * Replaces the payload. Intended for {@link PayloadMigrator} callbacks and
     * other server-thread code; see the threading contract in
     * {@link SegmentedGraph}.
     */
    default void setPayload(T payload) {
        throw new UnsupportedOperationException("This GraphSegment does not support replacing its payload");
    }

    /** Number of positions in {@link #positions()}, without materialising the list view. */
    default int positionCount() {
        return positions().size();
    }

    /** Packed {@link NodeId#raw()} of position {@code index}; allocation-free. */
    default long rawPositionAt(int index) {
        return positions().get(index).raw();
    }
}
