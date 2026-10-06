package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.topology.NodeId;

/**
 * The only point where the discrete engine tells a Minecraft-integration
 * layer "this changed, update the BlockState / fire comparators / etc."
 * The engine itself never calls anything in {@code net.minecraft.*}; the
 * consumer implements this in its own integration layer.
 */
@FunctionalInterface
public interface DiscreteGraphListener {

    void onLevelChanged(NodeId id, int oldLevel, int newLevel);
}
