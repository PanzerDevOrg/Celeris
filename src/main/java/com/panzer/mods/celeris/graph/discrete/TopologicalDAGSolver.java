package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.dirty.DirtyQueue;
import com.panzer.mods.celeris.core.topology.NetworkTopology;
import com.panzer.mods.celeris.util.math.BranchlessTables;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

/**
 * Cascading propagation in topological order over the dirty subgraph. Each
 * {@link #propagate()} call processes exactly the nodes reachable from
 * whatever is in {@link DirtyQueue} at call time -- a static network (empty
 * queue) costs one {@code isIdle()} check, not a traversal.
 *
 * <p>Kahn's algorithm is used for ordering rather than repeated relaxation
 * (Bellman-Ford style) because redstone's attenuation is monotonic
 * non-increasing along any path with no negative edges -- once a node's
 * strongest incoming level is known, its outgoing level is final for this
 * pass, so each node needs exactly one evaluation per pass, not "until it
 * stops changing".
 *
 * <p><b>Ordering rule (this implementation's concrete choice):</b> within
 * the discovered subgraph, an edge is considered to run from a neighbor
 * {@code m} into a node {@code n} exactly when {@code m}'s frozen
 * pre-pass signal level is strictly greater than {@code n}'s -- i.e. only
 * a strictly stronger neighbor is treated as a potential feeder this pass.
 * Because "strictly greater than" can never form a cycle on its own (it is
 * irreflexive and antisymmetric), the resulting edge set is always a DAG
 * regardless of the physical topology's own cycles (redstone loops, e.g.
 * two facing repeaters) -- a physical cycle only ever contributes
 * equal-level or opposing-direction edges here, both of which Kahn already
 * handles as independent zero-in-degree nodes. This reproduces the
 * "ticks-differed" feedback behavior real redstone loops need without any
 * special-cased cycle detection: a node in a physical loop is evaluated
 * against whichever of its neighbors are currently stronger, and if that
 * changes its own level, {@link #markDownstreamDirtyForNextPass} queues
 * its neighbors -- including ones already processed earlier this pass --
 * for another look next pass.
 *
 * <p><b>Known consequence of the ordering rule, worth stating plainly:</b>
 * because in-degree only counts a STRICTLY stronger discovered neighbor as
 * a feeder, two adjacent nodes that are still tied at their stale pre-pass
 * level (the common case one hop ahead of a wavefront that hasn't reached
 * them yet) get indegree 0 with respect to each other and are ordered
 * arbitrarily -- so a freshly-lit source's level only reaches its
 * immediate neighbor within the pass that lit it; nodes two or more hops
 * out are discovered (their dense index enters {@link #discoveredScratch})
 * but are not guaranteed to be evaluated after the node feeding them has
 * already updated in the SAME pass. In practice this means a straight run
 * settles one hop per {@link #propagate()} call, not its whole length in
 * one call -- {@code EventDrivenPipelineSolverImpl} accounts for this by
 * looping {@link #propagate()} up to its own per-tick cap rather than
 * assuming one call suffices; see that class's javadoc.
 */
@SuppressWarnings("JavadocReference")
public final class TopologicalDAGSolver {

    private final NetworkTopology topology;
    private final DiscreteNodeStore nodes;
    private final DirtyQueue dirtyQueue;
    @SuppressWarnings("FieldCanBeLocal")
    private final PropagationBudget budget;

    // Scratch buffers, reused across propagate() calls -- zero per-tick
    // allocation once warmed up, sized to PropagationBudget.maxNodesPerPass().
    private final int[] discoveredScratch;
    private final int[] inDegreeScratch;         // indexed by LOCAL slot [0,k), not denseIndex
    private final int[] topoOrderScratch;         // denseIndex, in evaluation order
    private final boolean[] emittedScratch;      // LOCAL slot -> already ordered
    private final int[] frozenLevelScratch;      // LOCAL slot -> pre-pass signal level (one store read per node per pass)
    private final int[] neighborScratch = new int[NetworkTopology.MAX_NEIGHBORS];
    // denseIndex -> local slot [0,k) or -1. A flat array sized to the
    // topology's dense capacity replaces the previous Int2IntOpenHashMap:
    // O(1) array index with no hashing on every neighbor probe. Only the
    // entries touched this pass are reset afterwards (via discoveredScratch).
    private int[] denseToLocal = new int[0];
    private final IntArrayFIFOQueue bfsFrontier;
    private final IntArrayFIFOQueue kahnFrontier;  // holds LOCAL slots with inDegree 0

    private DiscreteGraphImpl.LevelChangeSink levelChangeSink; // optional, set by DiscreteGraphImpl

    public TopologicalDAGSolver(NetworkTopology topology, DiscreteNodeStore nodes,
                                 DirtyQueue dirtyQueue, PropagationBudget budget) {
        this.topology = topology;
        this.nodes = nodes;
        this.dirtyQueue = dirtyQueue;
        this.budget = budget;
        int cap = budget.maxNodesPerPass();
        this.discoveredScratch = new int[cap];
        this.inDegreeScratch = new int[cap];
        this.topoOrderScratch = new int[cap];
        this.emittedScratch = new boolean[cap];
        this.frozenLevelScratch = new int[cap];
        this.bfsFrontier = new IntArrayFIFOQueue();
        this.kahnFrontier = new IntArrayFIFOQueue();
    }

    /** Grows (never shrinks) the dense->local table to cover the topology; only on growth events. */
    private void ensureLocalTable() {
        int need = topology.denseCapacity();
        if (need > denseToLocal.length) {
            int newLen = Math.max(64, Integer.highestOneBit(need - 1) << 1);
            int[] grown = new int[newLen];
            java.util.Arrays.fill(grown, -1);
            denseToLocal = grown;
        }
    }

    private int localOf(int denseIndex) {
        int[] t = denseToLocal;
        return denseIndex < t.length ? t[denseIndex] : -1;
    }

    void setLevelChangeSink(DiscreteGraphImpl.LevelChangeSink sink) {
        this.levelChangeSink = sink;
    }

    /**
     * One propagation pass. Returns the number of nodes whose signal level
     * actually changed (0 means the dirty set was a false alarm -- e.g. a
     * neighbor was marked dirty but its actual max-incoming level didn't
     * change -- and no further passes are scheduled from this one).
     */
    public int propagate() {
        if (dirtyQueue.isIdle()) {
            return 0; // the whole point: static network costs one branch, not a traversal
        }

        ensureLocalTable();
        int discoveredCount = discoverAffectedSubgraph();
        if (discoveredCount == 0) {
            return 0;
        }
        int orderCount = kahnTopologicalSort(discoveredCount);

        int changedCount = 0;
        int[] nb = neighborScratch;
        for (int i = 0; i < orderCount; i++) {
            int denseIndex = topoOrderScratch[i];
            int newLevel = computeIncomingLevel(denseIndex, nb);
            int oldLevel = nodes.signalLevel(denseIndex);
            if (newLevel != oldLevel) {
                nodes.setSignalLevel(denseIndex, newLevel);
                changedCount++;
                if (levelChangeSink != null) {
                    levelChangeSink.onChanged(denseIndex, oldLevel, newLevel);
                }
                int n = topology.neighborDenseIndices(denseIndex, nb);
                for (int k = 0; k < n; k++) {
                    dirtyQueue.markDirty(nb[k]);
                }
            }
        }

        // Reset only the table entries this pass touched.
        int[] t = denseToLocal;
        for (int i = 0; i < discoveredCount; i++) {
            t[discoveredScratch[i]] = -1;
        }
        return changedCount;
    }

    /** Max over all incoming neighbor edges of attenuate(neighborLevel), or the node's own source level if isSource. */
    private int computeIncomingLevel(int denseIndex, int[] nb) {
        int packed = nodes.packedState(denseIndex);
        if (DiscreteNodeStore.isSourceBits(packed)) {
            return DiscreteNodeStore.signalBits(packed); // sources hold their own level, never decay from neighbors
        }
        int strongest = 0;
        int n = topology.neighborDenseIndices(denseIndex, nb);
        for (int k = 0; k < n; k++) {
            int candidate = SignalEdge.attenuate(nodes.signalLevel(nb[k]));
            strongest = BranchlessTables.max(strongest, candidate);
        }
        return strongest;
    }

    /**
     * Drains up to {@code budget.maxNodesPerPass()} dirty indices, then BFS
     * outward over physical adjacency (also capped by the same budget) so
     * a single pass can cascade a change across several hops of straight
     * wire in one server tick, instead of needing one tick per hop.
     * Populates {@link #discoveredScratch} and {@link #denseToLocal}.
     */
    private int discoverAffectedSubgraph() {
        bfsFrontier.clear();

        int count = 0;
        int cap = discoveredScratch.length;
        int[] t = denseToLocal;
        int[] nb = neighborScratch;

        int seed;
        while (count < cap && (seed = dirtyQueue.poll()) != -1) {
            if (seed >= t.length) {
                continue; // index beyond current topology (node removed + shrunk view); nothing to evaluate
            }
            if (t[seed] == -1) {
                t[seed] = count;
                discoveredScratch[count] = seed;
                frozenLevelScratch[count] = nodes.signalLevel(seed);
                count++;
                bfsFrontier.enqueue(seed);
            }
        }

        while (!bfsFrontier.isEmpty() && count < cap) {
            int current = bfsFrontier.dequeueInt();
            int n = topology.neighborDenseIndices(current, nb);
            for (int k = 0; k < n && count < cap; k++) {
                int neighborIdx = nb[k];
                if (t[neighborIdx] == -1) {
                    t[neighborIdx] = count;
                    discoveredScratch[count] = neighborIdx;
                    frozenLevelScratch[count] = nodes.signalLevel(neighborIdx);
                    count++;
                    bfsFrontier.enqueue(neighborIdx);
                }
            }
        }

        // Any dirty indices left in the ring beyond the budget stay queued
        // for the next tick's pass -- correctness (eventual consistency)
        // holds, only latency degrades under pathological load.
        return count;
    }

    /** Kahn's algorithm over the discovered set; returns how many nodes were ordered into {@link #topoOrderScratch}. */
    private int kahnTopologicalSort(int discoveredCount) {
        java.util.Arrays.fill(inDegreeScratch, 0, discoveredCount, 0);
        java.util.Arrays.fill(emittedScratch, 0, discoveredCount, false);
        kahnFrontier.clear();

        int[] t = denseToLocal;
        int[] nb = neighborScratch;
        int[] frozen = frozenLevelScratch;
        boolean[] emitted = emittedScratch;

        // In-degree(n) = count of discovered neighbors m with frozen level(m) > frozen level(n).
        // Levels come from frozenLevelScratch (captured at discovery) -- one
        // off-heap read per node per pass instead of one per edge visit.
        for (int local = 0; local < discoveredCount; local++) {
            int ownLevel = frozen[local];
            int n = topology.neighborDenseIndices(discoveredScratch[local], nb);
            for (int k = 0; k < n; k++) {
                int neighborLocal = t[nb[k]];
                if (neighborLocal < 0) {
                    continue; // neighbor outside the discovered/budgeted set this pass
                }
                if (frozen[neighborLocal] > ownLevel) {
                    inDegreeScratch[local]++;
                }
            }
        }

        for (int local = 0; local < discoveredCount; local++) {
            if (inDegreeScratch[local] == 0) {
                kahnFrontier.enqueue(local);
            }
        }

        int ordered = 0;
        while (!kahnFrontier.isEmpty()) {
            int local = kahnFrontier.dequeueInt();
            if (emitted[local]) {
                continue;
            }
            emitted[local] = true;
            int denseIndex = discoveredScratch[local];
            topoOrderScratch[ordered++] = denseIndex;

            int ownLevel = frozen[local];
            int n = topology.neighborDenseIndices(denseIndex, nb);
            for (int k = 0; k < n; k++) {
                int neighborLocal = t[nb[k]];
                if (neighborLocal < 0 || emitted[neighborLocal]) {
                    continue;
                }
                // An edge from this node into neighbor exists (for ordering
                // purposes) exactly when this node is currently the
                // strictly-stronger side.
                if (ownLevel > frozen[neighborLocal]) {
                    if (--inDegreeScratch[neighborLocal] == 0) {
                        kahnFrontier.enqueue(neighborLocal);
                    }
                }
            }
        }

        // Any leftover nodes (only possible if two discovered neighbors
        // have exactly equal levels and both ended up depending on a third
        // still-pending node in a way the strict-> edge relation never
        // resolves) are appended in discovery order as a safety net -- this
        // never happens with the strict-inequality edge rule above (which
        // cannot cycle), but the fallback keeps propagate() total even if
        // a future edge-weighting change reintroduces the possibility.
        if (ordered < discoveredCount) {
            for (int local = 0; local < discoveredCount; local++) {
                if (!emitted[local]) {
                    emitted[local] = true;
                    topoOrderScratch[ordered++] = discoveredScratch[local];
                }
            }
        }

        return ordered;
    }
}
