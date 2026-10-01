package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.core.topology.NodeId;

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
}
