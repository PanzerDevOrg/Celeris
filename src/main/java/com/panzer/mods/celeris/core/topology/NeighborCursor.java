package com.panzer.mods.celeris.core.topology;

/**
 * Allocation-free cursor over a node's physically-reciprocal neighbors --
 * same spirit as the boxing-free {@code Iterable<Direction>} the segmented
 * pipeline's {@code GraphNodeState.connections()} already returns, but
 * shaped as an explicit cursor (call {@link #next()} until it returns
 * {@code false}) instead of an {@code Iterator}, so a hot loop like
 * {@code TopologicalDAGSolver.computeIncomingLevel} never allocates an
 * iterator object per node visited.
 *
 * <p>A neighbor is only ever reported when the connection is reciprocal --
 * this node has a connection bit toward the neighbor AND the neighbor has
 * the opposite bit set back toward this node. A one-sided connection bit
 * (e.g. the far side lost its connection but hasn't been re-traced yet)
 * is not a real physical link and is never surfaced here, matching the
 * reciprocity check in the segmented pipeline's {@code traceSegment}.
 */
public interface NeighborCursor {

    /** Advances to the next reciprocal neighbor. Returns {@code false} once exhausted. */
    boolean next();

    /** Direction index (from the queried node toward this neighbor) for the current position. */
    int directionIndex();

    /** The neighbor's NodeId for the current position. */
    NodeId neighborId();

    /** The neighbor's dense index for the current position -- avoids a second map lookup in hot loops. */
    int neighborDenseIndex();
}
