package com.panzer.mods.celeris.graph.discrete;

/**
 * Hard cap on how much of the network a single {@code propagate()} call may
 * touch. Exists so a pathological network (a redstone clock feeding a huge
 * combinational tree) can't stall the server tick indefinitely -- if the
 * affected subgraph exceeds the budget, discovery truncates and the
 * remaining dirty nodes stay queued for the NEXT pass instead of being
 * dropped, so correctness (eventual consistency) holds, only latency
 * degrades under pathological load.
 */
public record PropagationBudget(int maxNodesPerPass) {

    public static PropagationBudget standard() {
        return new PropagationBudget(4096);
    }
}
