package com.panzer.mods.celeris.core.topology;

/**
 * Fired by {@link NetworkTopology} on every mutation, before either
 * consuming solver (fusion in graph/segmented, per-node evaluation in
 * graph/discrete) reacts to it. Both SegmentedGraphImpl and
 * DiscreteGraphImpl attach here instead of each re-detecting "what
 * changed" independently against their own bookkeeping.
 *
 * <p>{@code after == null} signals a removal (the node no longer exists);
 * a real connection-mask update, including one to an all-zero mask, always
 * passes a non-null {@code after}. {@code before == null} signals the node
 * did not previously exist (a fresh {@code addNode}).
 */
@FunctionalInterface
public interface TopologyListener {

    void onMutated(NodeId id, ConnectionMask before, ConnectionMask after);
}
