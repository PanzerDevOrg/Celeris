package com.panzer.mods.celeris.pipeline.discrete;

import com.panzer.mods.celeris.pipeline.PipelineSolver;

/**
 * Ticking strategy over a {@link com.panzer.mods.celeris.graph.discrete.DiscreteGraph}:
 * drains its {@code DirtyQueue} through {@code TopologicalDAGSolver#propagate()}.
 * Unlike {@code SIMDPipelineSolver}, there is nothing to bind beyond the
 * solver/queue pair -- {@code DiscreteGraphImpl} already owns and exposes
 * both (see its javadoc), so this is a thin driver, not a second copy of
 * discrete-graph state.
 */
public interface EventDrivenPipelineSolver extends PipelineSolver {
}
