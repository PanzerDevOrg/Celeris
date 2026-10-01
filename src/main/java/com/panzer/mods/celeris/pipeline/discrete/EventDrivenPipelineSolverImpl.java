package com.panzer.mods.celeris.pipeline.discrete;

import com.panzer.mods.celeris.core.dirty.DirtyQueue;
import com.panzer.mods.celeris.graph.discrete.TopologicalDAGSolver;

/**
 * Default {@link EventDrivenPipelineSolver}: repeatedly calls {@code
 * TopologicalDAGSolver#propagate()} within a single {@link #tick()} until
 * either the dirty queue empties or {@link #MAX_PASSES_PER_TICK} is hit.
 *
 * <p>Looping propagate() to a fixed point within one tick is what lets a
 * long straight wire settle in a single game tick instead of one tick per
 * hop. A single {@code propagate()} call does NOT fully settle a whole
 * straight run by itself -- per {@link TopologicalDAGSolver}'s javadoc, a
 * freshly-changed node's new level only reaches its immediate neighbor
 * within the pass that changed it, so a run of length N needs on the order
 * of N internal {@code propagate()} calls, which this loop provides (up to
 * {@link #MAX_PASSES_PER_TICK}). Since vanilla redstone's own attenuation
 * range caps any single run's meaningful length at 15 hops from a source,
 * {@link #MAX_PASSES_PER_TICK} comfortably covers every run that can
 * actually carry a nonzero signal.
 *
 * <p>{@link #MAX_PASSES_PER_TICK} exists specifically for genuinely cyclic
 * circuits (two nodes whose outputs feed each other's inputs with no
 * deferred delay breaking the loop) which would otherwise keep finding new
 * dirty work forever within a single {@code tick()} call -- letting that
 * settle "instantly" would make a redstone loop flip an unbounded number
 * of times in one game tick, a correctness regression relative to vanilla
 * (where such a loop is gated by real per-tick delay from repeaters/torch
 * burnout). Capping passes per tick bounds it to at most this many state
 * flips per game tick instead, same defensive-budget philosophy as {@code
 * PropagationBudget} for a single pass's node count.
 */
public final class EventDrivenPipelineSolverImpl implements EventDrivenPipelineSolver {

    private static final int MAX_PASSES_PER_TICK = 64;

    private final TopologicalDAGSolver solver;
    private final DirtyQueue dirtyQueue;

    public EventDrivenPipelineSolverImpl(TopologicalDAGSolver solver, DirtyQueue dirtyQueue) {
        this.solver = solver;
        this.dirtyQueue = dirtyQueue;
    }

    @Override
    public void tick() {
        for (int pass = 0; pass < MAX_PASSES_PER_TICK; pass++) {
            int changed = solver.propagate();
            if (changed == 0) {
                return;
            }
        }
    }

    @Override
    public boolean isIdle() {
        return dirtyQueue.isIdle();
    }

    @Override
    public String name() {
        return "event-driven discrete";
    }
}
