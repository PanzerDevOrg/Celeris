package com.panzer.mods.celeris.pipeline.transit;

import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.PayloadMigrator;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.List;

/**
 * Carries items across graph mutations: when segments are split, merged or
 * rebuilt, every item on a removed segment is placed on the new segment that
 * covers the same stretch of world, with the same speed and the same distance
 * along that stretch. No item is dropped or duplicated.
 *
 * <p>An item standing on a stretch that no new segment covers (its block was
 * removed, or the run was left without a valid end for a moment while several
 * blocks update) waits as an orphan. Every later mutation retries it. The
 * solver reports the ones still waiting as lost at the start of its next tick
 * through the {@link LostSink}, which is where a consumer drops the item into
 * the world.
 *
 * <p>Pass the same instance to {@code SegmentedGraphImpl} (as migrator) and to
 * {@link TransitSolver}. Runs on the graph's thread; reuses its scratch
 * structures, so steady-state migrations allocate nothing.
 */
public final class TransitMigrator implements PayloadMigrator<TransitPayload> {

    /** Receives items that could not stay in the network. */
    @FunctionalInterface
    public interface LostSink {
        /** {@code nodeRaw} is the packed position the item was on when it was lost. */
        void itemLost(int handle, float speed, long nodeRaw);
    }

    private final LostSink lostSink;

    // Index of the new segments' positions: raw position -> first entry; entries chain through entryNext.
    private final Long2IntOpenHashMap firstEntry = new Long2IntOpenHashMap();
    private int[] entrySegment = new int[64];
    private int[] entryIndex = new int[64];
    private int[] entryNext = new int[64];
    private int entryCount;

    // Orphans: travel from `from` to `to`, `along` of the way (0..1).
    private int[] orphanHandle = new int[8];
    private float[] orphanSpeed = new float[8];
    private float[] orphanAlong = new float[8];
    private long[] orphanFrom = new long[8];
    private long[] orphanTo = new long[8];
    private int orphanCount;

    public TransitMigrator(LostSink lostSink) {
        this.lostSink = lostSink;
        firstEntry.defaultReturnValue(-1);
    }

    @Override
    public void migrate(List<GraphSegment<TransitPayload>> removed, List<GraphSegment<TransitPayload>> created) {
        indexCreated(created);
        // Earlier orphans first: they have waited longest.
        for (int i = orphanCount - 1; i >= 0; i--) {
            if (place(created, orphanHandle[i], orphanSpeed[i], orphanFrom[i], orphanTo[i], orphanAlong[i])) {
                removeOrphan(i);
            }
        }
        for (int r = 0, rn = removed.size(); r < rn; r++) {
            GraphSegment<TransitPayload> old = removed.get(r);
            TransitPayload payload = old.payload();
            moveQueue(old, payload.forward(), true, created);
            moveQueue(old, payload.backward(), false, created);
        }
        firstEntry.clear();
        entryCount = 0;
    }

    /**
     * Reports every orphan still waiting as lost and forgets it. Returns how
     * many. The solver calls this at the start of each tick.
     */
    public int flushOrphans() {
        int n = orphanCount;
        for (int i = 0; i < n; i++) {
            long where = orphanAlong[i] < 0.5f ? orphanFrom[i] : orphanTo[i];
            lostSink.itemLost(orphanHandle[i], orphanSpeed[i], where);
        }
        orphanCount = 0;
        return n;
    }

    /** Items waiting for a segment to be rebuilt. */
    public int orphanCount() {
        return orphanCount;
    }

    private void moveQueue(GraphSegment<TransitPayload> old, TransitQueue queue, boolean forward,
                           List<GraphSegment<TransitPayload>> created) {
        int length = old.length();
        for (int i = 0, n = queue.size(); i < n; i++) {
            float progress = queue.progressAt(i);
            // k-th edge travelled (an item exactly on a node belongs to the edge it just crossed)
            int k = Math.max(0, (int) Math.ceil(progress) - 1);
            if (k > length - 1) {
                k = length - 1;
            }
            float along = progress - k;
            long from = old.rawPositionAt(forward ? k : length - k);
            long to = old.rawPositionAt(forward ? k + 1 : length - k - 1);
            int handle = queue.handleAt(i);
            float speed = queue.speedAt(i);
            if (!place(created, handle, speed, from, to, along)) {
                addOrphan(handle, speed, from, to, along);
            }
        }
        queue.clear();
    }

    private void indexCreated(List<GraphSegment<TransitPayload>> created) {
        entryCount = 0;
        for (int s = 0, sn = created.size(); s < sn; s++) {
            GraphSegment<TransitPayload> segment = created.get(s);
            for (int p = 0, pn = segment.positionCount(); p < pn; p++) {
                if (entryCount == entrySegment.length) {
                    int grown = entryCount << 1;
                    entrySegment = Arrays.copyOf(entrySegment, grown);
                    entryIndex = Arrays.copyOf(entryIndex, grown);
                    entryNext = Arrays.copyOf(entryNext, grown);
                }
                long raw = segment.rawPositionAt(p);
                entrySegment[entryCount] = s;
                entryIndex[entryCount] = p;
                entryNext[entryCount] = firstEntry.put(raw, entryCount);
                entryCount++;
            }
        }
    }

    /** Puts the item on the new segment that has the edge from -> to; false if there is none. */
    private boolean place(List<GraphSegment<TransitPayload>> created, int handle, float speed,
                          long from, long to, float along) {
        for (int e = firstEntry.get(from); e >= 0; e = entryNext[e]) {
            GraphSegment<TransitPayload> segment = created.get(entrySegment[e]);
            int p = entryIndex[e];
            int count = segment.positionCount();
            if (p + 1 < count && segment.rawPositionAt(p + 1) == to) {
                segment.payload().forward().insertSorted(handle, speed, p + along);
                return true;
            }
            if (p > 0 && segment.rawPositionAt(p - 1) == to) {
                // travelling toward the segment's A end: edges are counted from B
                int travelled = segment.length() - p;
                segment.payload().backward().insertSorted(handle, speed, travelled + along);
                return true;
            }
        }
        return false;
    }

    private void addOrphan(int handle, float speed, long from, long to, float along) {
        if (orphanCount == orphanHandle.length) {
            int grown = orphanCount << 1;
            orphanHandle = Arrays.copyOf(orphanHandle, grown);
            orphanSpeed = Arrays.copyOf(orphanSpeed, grown);
            orphanAlong = Arrays.copyOf(orphanAlong, grown);
            orphanFrom = Arrays.copyOf(orphanFrom, grown);
            orphanTo = Arrays.copyOf(orphanTo, grown);
        }
        orphanHandle[orphanCount] = handle;
        orphanSpeed[orphanCount] = speed;
        orphanAlong[orphanCount] = along;
        orphanFrom[orphanCount] = from;
        orphanTo[orphanCount] = to;
        orphanCount++;
    }

    private void removeOrphan(int i) {
        int last = --orphanCount;
        if (i != last) {
            orphanHandle[i] = orphanHandle[last];
            orphanSpeed[i] = orphanSpeed[last];
            orphanAlong[i] = orphanAlong[last];
            orphanFrom[i] = orphanFrom[last];
            orphanTo[i] = orphanTo[last];
        }
    }
}
