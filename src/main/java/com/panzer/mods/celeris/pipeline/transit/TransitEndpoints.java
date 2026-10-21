package com.panzer.mods.celeris.pipeline.transit;

/**
 * Where items leave the network: inventories, machines, the ground. A terminal
 * is a direction from a node that does not lead to another segment (an
 * inventory next to a pipe end is not part of the graph).
 *
 * <p>{@link #deliver} may refuse; the item then waits at the node and is
 * offered again on later ticks (possibly to another exit), so refusal never
 * loses it. Runs on the graph's thread.
 */
public interface TransitEndpoints {

    /** An endpoints object with no terminals. */
    TransitEndpoints NONE = new TransitEndpoints() {
        @Override
        public int terminalMask(long nodeRaw) {
            return 0;
        }

        @Override
        public boolean deliver(long nodeRaw, int direction, int handle) {
            return false;
        }
    };

    /** Bit mask over directions 0..5 that are terminals at this node. Must be cheap and allocation-free. */
    int terminalMask(long nodeRaw);

    /** Tries to hand the item to the terminal in {@code direction}. True when taken (the handle is then the endpoint's). */
    boolean deliver(long nodeRaw, int direction, int handle);
}
