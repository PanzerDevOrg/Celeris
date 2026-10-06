package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.util.math.BranchlessTables;

/**
 * A directed propagation edge: source dense-index -> target dense-index.
 * Not stored as objects in bulk -- this record exists for the solver's
 * local reasoning; the actual edge set is read directly off {@code
 * NetworkTopology}'s adjacency, attenuation computed inline, never
 * materialized as {@code SignalEdge[]} in the hot path.
 */
public record SignalEdge(int sourceDenseIndex, int targetDenseIndex) {

    /** Standard 15->0 attenuation, one level per hop, floored at 0 -- branchless via BranchlessTables.max. */
    public static int attenuate(int sourceLevel) {
        return BranchlessTables.max(sourceLevel - 1, 0);
    }
}
