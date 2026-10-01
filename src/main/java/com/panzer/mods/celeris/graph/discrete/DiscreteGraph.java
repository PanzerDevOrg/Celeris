package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NodeId;

/**
 * Public contract for the discrete/event-driven paradigm. Deliberately NOT
 * shaped like {@code SegmentedGraph} -- no {@code segments()}, no fusion
 * queries -- because every position is individually addressable and
 * individually meaningful here; collapsing would be the bug this whole
 * design avoids (see the architecture doc's diagnosis).
 */
public interface DiscreteGraph {

    void addNode(NodeId id, ConnectionMask connections, boolean isSource);

    void removeNode(NodeId id);

    void updateConnections(NodeId id, ConnectionMask connections);

    /** External signal write -- e.g. a lever flipped, a block's own power changed. Marks id dirty. */
    void setSourceLevel(NodeId id, int level);

    int signalLevelAt(NodeId id);

    /** Registers a listener fired once per changed node per propagate() pass. */
    void addListener(DiscreteGraphListener listener);
}
