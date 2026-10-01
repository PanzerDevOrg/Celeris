package com.panzer.mods.celeris.core.topology;

import com.panzer.mods.celeris.util.math.BranchlessTables;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Default NetworkTopology: dense-indexed adjacency over primitive arrays.
 *
 * <p>Dense-index assignment is append-only with free-list reuse (never
 * shrinks the backing arrays on removeNode -- a freed index is pushed onto
 * {@code freeIndices} and reused by the next addNode), so denseIndexOf
 * results stay stable across unrelated mutations elsewhere in the graph.
 * This matters because {@code DiscreteNodeStore} (graph/discrete) indexes
 * its own parallel SoA arrays by this same dense index -- a reassignment
 * would silently corrupt unrelated node state.
 *
 * <p>Neighbor resolution is computed on demand from a node's own
 * {@code ConnectionMask} (step + hash lookup per set direction bit, at
 * most 6 per node) rather than a materialized CSR adjacency array. For the
 * degree bound this graph ever has (six cardinal directions), a lookup in
 * {@code denseIndexById} per candidate direction is exactly as cheap as
 * walking a precomputed flat array would be, without the invalidation
 * bookkeeping a cached CSR structure would need on every structural
 * mutation -- so this deliberately trades a theoretically-flatter layout
 * for a simpler, still allocation-free and still O(1)-per-neighbor,
 * implementation.
 */
public final class NetworkTopologyImpl implements NetworkTopology {

    private final Long2IntOpenHashMap denseIndexById = new Long2IntOpenHashMap();
    private long[] idByDenseIndex = new long[64];
    private int[] connectionMaskByDenseIndex = new int[64];
    private boolean[] liveByDenseIndex = new boolean[64];

    private final IntArrayList freeIndices = new IntArrayList();
    private int nextFreshIndex;

    private final List<TopologyListener> listeners = new ArrayList<>(2);

    public NetworkTopologyImpl() {
        denseIndexById.defaultReturnValue(-1);
    }

    @Override
    public void addNode(NodeId id, ConnectionMask connections) {
        int existing = denseIndexById.get(id.raw());
        if (existing >= 0) {
            updateConnections(id, connections);
            return;
        }
        int idx = allocateIndex();
        denseIndexById.put(id.raw(), idx);
        idByDenseIndex[idx] = id.raw();
        connectionMaskByDenseIndex[idx] = connections.bits();
        liveByDenseIndex[idx] = true;
        fireMutation(id, null, connections);
    }

    @Override
    public void removeNode(NodeId id) {
        int idx = denseIndexById.get(id.raw());
        if (idx < 0) {
            return;
        }
        ConnectionMask before = ConnectionMask.of(connectionMaskByDenseIndex[idx]);
        denseIndexById.remove(id.raw());
        connectionMaskByDenseIndex[idx] = 0;
        idByDenseIndex[idx] = 0L;
        liveByDenseIndex[idx] = false;
        freeIndices.add(idx);
        fireMutation(id, before, null);
    }

    @Override
    public void updateConnections(NodeId id, ConnectionMask connections) {
        int idx = denseIndexById.get(id.raw());
        if (idx < 0) {
            addNode(id, connections);
            return;
        }
        ConnectionMask before = ConnectionMask.of(connectionMaskByDenseIndex[idx]);
        connectionMaskByDenseIndex[idx] = connections.bits();
        fireMutation(id, before, connections);
    }

    @Override
    public int denseIndexOf(NodeId id) {
        return denseIndexById.get(id.raw());
    }

    @Override
    public NodeId nodeIdAt(int denseIndex) {
        if (denseIndex < 0 || denseIndex >= nextFreshIndex || !liveByDenseIndex[denseIndex]) {
            return null;
        }
        return new NodeId(idByDenseIndex[denseIndex]);
    }

    @Override
    public ConnectionMask connectionsOf(NodeId id) {
        int idx = denseIndexById.get(id.raw());
        if (idx < 0) {
            return ConnectionMask.NONE;
        }
        return ConnectionMask.of(connectionMaskByDenseIndex[idx]);
    }

    @Override
    public NeighborCursor neighborsOf(NodeId id) {
        int idx = denseIndexById.get(id.raw());
        if (idx < 0) {
            return EmptyCursor.INSTANCE;
        }
        return new DenseNeighborCursor(idx);
    }

    @Override
    public NeighborCursor neighborsOfDense(int denseIndex) {
        if (denseIndex < 0 || denseIndex >= nextFreshIndex || !liveByDenseIndex[denseIndex]) {
            return EmptyCursor.INSTANCE;
        }
        return new DenseNeighborCursor(denseIndex);
    }

    /**
     * Hot-loop neighbor resolution: walks the mask bits on packed longs
     * only -- no cursor, no {@link NodeId}, one hash probe per set bit.
     */
    @Override
    public int neighborDenseIndices(int denseIndex, int[] out) {
        if (denseIndex < 0 || denseIndex >= nextFreshIndex || !liveByDenseIndex[denseIndex]) {
            return 0;
        }
        int n = 0;
        long originRaw = idByDenseIndex[denseIndex];
        int remaining = connectionMaskByDenseIndex[denseIndex];
        while (remaining != 0) {
            int dir = Integer.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1;
            int candidateIdx = denseIndexById.get(NodeId.stepRaw(originRaw, dir));
            if (candidateIdx < 0) {
                continue;
            }
            if ((connectionMaskByDenseIndex[candidateIdx] & (1 << BranchlessTables.opposite(dir))) == 0) {
                continue; // not reciprocal yet
            }
            out[n++] = candidateIdx;
        }
        return n;
    }

    @Override
    public int nodeCount() {
        return denseIndexById.size();
    }

    @Override
    public int denseCapacity() {
        return nextFreshIndex;
    }

    @Override
    public void addTopologyListener(TopologyListener listener) {
        listeners.add(listener);
    }

    private void fireMutation(NodeId id, ConnectionMask before, ConnectionMask after) {
        for (TopologyListener listener : listeners) {
            listener.onMutated(id, before, after);
        }
    }

    private int allocateIndex() {
        if (!freeIndices.isEmpty()) {
            return freeIndices.removeInt(freeIndices.size() - 1);
        }
        int idx = nextFreshIndex++;
        ensureCapacity(idx + 1);
        return idx;
    }

    private void ensureCapacity(int required) {
        if (required <= idByDenseIndex.length) {
            return;
        }
        int newCapacity = Integer.highestOneBit(required - 1) << 1;
        long[] grownIds = new long[newCapacity];
        int[] grownMasks = new int[newCapacity];
        boolean[] grownLive = new boolean[newCapacity];
        System.arraycopy(idByDenseIndex, 0, grownIds, 0, idByDenseIndex.length);
        System.arraycopy(connectionMaskByDenseIndex, 0, grownMasks, 0, connectionMaskByDenseIndex.length);
        System.arraycopy(liveByDenseIndex, 0, grownLive, 0, liveByDenseIndex.length);
        idByDenseIndex = grownIds;
        connectionMaskByDenseIndex = grownMasks;
        liveByDenseIndex = grownLive;
    }

    private static final class EmptyCursor implements NeighborCursor {
        static final EmptyCursor INSTANCE = new EmptyCursor();

        @Override
        public boolean next() {
            return false;
        }

        @Override
        public int directionIndex() {
            throw new IllegalStateException("next() was never true");
        }

        @Override
        public NodeId neighborId() {
            throw new IllegalStateException("next() was never true");
        }

        @Override
        public int neighborDenseIndex() {
            throw new IllegalStateException("next() was never true");
        }
    }

    /**
     * Walks the up-to-six connection-mask bits of the node at {@code
     * originDenseIndex}, skipping any direction whose physical neighbor is
     * unregistered or does not itself carry the opposite bit (a one-sided,
     * not-yet-reciprocal connection).
     */
    private final class DenseNeighborCursor implements NeighborCursor {
        private final long originRaw;
        private int remaining;
        private int currentDirection = -1;
        private long currentNeighborRaw;
        private int currentNeighborDenseIndex;

        DenseNeighborCursor(int originDenseIndex) {
            this.remaining = connectionMaskByDenseIndex[originDenseIndex];
            this.originRaw = idByDenseIndex[originDenseIndex];
        }

        @Override
        public boolean next() {
            while (remaining != 0) {
                int dir = Integer.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1;

                long candidateRaw = NodeId.stepRaw(originRaw, dir);
                int candidateIdx = denseIndexById.get(candidateRaw);
                if (candidateIdx < 0) {
                    continue;
                }
                if ((connectionMaskByDenseIndex[candidateIdx] & (1 << BranchlessTables.opposite(dir))) == 0) {
                    continue; // not reciprocal yet
                }

                currentDirection = dir;
                currentNeighborRaw = candidateRaw;
                currentNeighborDenseIndex = candidateIdx;
                return true;
            }
            return false;
        }

        @Override
        public int directionIndex() {
            return currentDirection;
        }

        /** Allocates only when called -- hot loops use {@link #neighborDenseIndex()}. */
        @Override
        public NodeId neighborId() {
            return new NodeId(currentNeighborRaw);
        }

        @Override
        public int neighborDenseIndex() {
            return currentNeighborDenseIndex;
        }
    }
}
