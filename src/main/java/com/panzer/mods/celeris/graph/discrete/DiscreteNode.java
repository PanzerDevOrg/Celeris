package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.topology.NodeId;

/**
 * Immutable point-in-time snapshot of a discrete node's state, for callers
 * that want a plain value (external queries, diagnostics, tests) rather
 * than reaching into {@link DiscreteNodeStore}'s packed-byte SoA storage
 * directly. Never held long-term or used on the hot propagation path --
 * {@link TopologicalDAGSolver} reads {@link DiscreteNodeStore} directly for
 * that, exactly to avoid allocating one of these per node per pass.
 */
public record DiscreteNode(NodeId id, int signalLevel, boolean isSource, boolean hasDeferredDelay, boolean locked) {
}
