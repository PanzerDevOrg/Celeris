package com.panzer.mods.celeris.pipeline.transit;

/**
 * Decides where an item goes when it reaches a node. Called by the
 * {@link TransitSolver} once per waiting item, per tick, so implementations
 * must not allocate.
 *
 * <p>Directions are the {@code BranchlessTables} indices 0..5; {@code candidates}
 * is a bit mask over them. A bit is set for each direction that leads to another
 * segment or to a terminal ({@link TransitEndpoints}), except the way the item
 * came from. Return one of the set bits, or -1 to make the item wait. The same
 * state and inputs must give the same answer: determinism of the whole
 * simulation depends on it.
 *
 * <p>If the chosen exit cannot take the item right now (full, rejecting), the
 * solver clears that bit and asks again, so a router never has to track
 * failure; it is only told about the exit that finally accepted
 * ({@link #committed}).
 */
public interface JunctionRouter {

    /**
     * @param nodeRaw  packed {@code NodeId.raw()} of the junction
     * @param fromDir  direction, seen from the node, of the segment the item arrived on
     * @param handle   the item's handle
     * @param candidates mask of directions the item may leave by (never empty)
     * @return a direction in {@code candidates}, or -1 to wait
     */
    int route(long nodeRaw, int fromDir, int handle, int candidates);

    /** The item left {@code nodeRaw} through {@code direction}. Round-robin style routers advance here. */
    default void committed(long nodeRaw, int direction, int handle) {
    }
}
