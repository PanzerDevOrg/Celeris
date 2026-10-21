package com.panzer.mods.celeris.api.transit;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.Arrays;

/**
 * What a client knows about the items in a transit network, kept up to date by
 * {@link TransitDelta} messages. One slot per item in flat arrays; read a slot
 * every frame and feed it to {@link TransitMotion#progress}. Reference
 * implementation of a {@link TransitDelta.Listener}; copy or extend it if a
 * renderer needs more per item.
 *
 * <p>Bounded: more than {@link #MAX_ITEMS} items are ignored, so a hostile or
 * buggy server cannot make a client grow without limit. Not thread-safe;
 * use it from the thread that applies packets and renders.
 */
public final class ClientItemTable implements TransitDelta.Listener {

    public static final int MAX_ITEMS = 1 << 20;

    private final Int2IntOpenHashMap slotByItem = new Int2IntOpenHashMap();
    private int[] item = new int[64];
    private long[] segment = new long[64];
    private boolean[] forward = new boolean[64];
    private float[] startProgress = new float[64];
    private float[] speed = new float[64];
    private long[] entryTick = new long[64];
    private long[] arrival = new long[64];
    private long nextArrival;
    private int[] freeSlots = new int[64];
    private int freeCount;
    private int slotCount;

    public ClientItemTable() {
        slotByItem.defaultReturnValue(-1);
    }

    /** Validates and applies a message; false (nothing applied) if it is malformed. */
    public boolean apply(byte[] data, int length) {
        return TransitDelta.read(data, length, this);
    }

    /** Number of items currently known. */
    public int size() {
        return slotByItem.size();
    }

    /** Upper bound (exclusive) of slot indices, for iteration with {@link #isLive}. */
    public int slotLimit() {
        return slotCount;
    }

    public boolean isLive(int slot) {
        return slot >= 0 && slot < slotCount && item[slot] >= 0;
    }

    /** Slot of an item, or -1. */
    public int slotOf(int itemKey) {
        return slotByItem.get(itemKey);
    }

    public int itemAt(int slot) {
        return item[slot];
    }

    public long segmentAt(int slot) {
        return segment[slot];
    }

    public boolean forwardAt(int slot) {
        return forward[slot];
    }

    public float startProgressAt(int slot) {
        return startProgress[slot];
    }

    public float speedAt(int slot) {
        return speed[slot];
    }

    public long entryTickAt(int slot) {
        return entryTick[slot];
    }

    /**
     * Order in which the item reached its current segment (smaller = earlier = further ahead in the queue).
     * Sort a segment's items by this, head first, to apply {@link TransitMotion#limitBehind}.
     */
    public long arrivalAt(int slot) {
        return arrival[slot];
    }

    /** Progress of the item in {@code slot} at {@code now}, on a segment {@code length} blocks long. */
    public float progressAt(int slot, double now, float length) {
        return TransitMotion.progress(startProgress[slot], speed[slot], entryTick[slot], now, length);
    }

    @Override
    public void enter(int itemKey, long seg, boolean fwd, float progress, float spd, long tick) {
        put(itemKey, seg, fwd, progress, spd, tick);
    }

    @Override
    public void move(int itemKey, long seg, boolean fwd, float progress, float spd, long tick) {
        put(itemKey, seg, fwd, progress, spd, tick);
    }

    @Override
    public void leave(int itemKey, long tick) {
        int slot = slotByItem.remove(itemKey);
        if (slot >= 0) {
            item[slot] = -1;
            if (freeCount == freeSlots.length) {
                freeSlots = Arrays.copyOf(freeSlots, freeCount << 1);
            }
            freeSlots[freeCount++] = slot;
        }
    }

    @Override
    public void speed(int itemKey, float progress, float spd, long tick) {
        int slot = slotByItem.get(itemKey);
        if (slot >= 0) {
            startProgress[slot] = progress;
            speed[slot] = spd;
            entryTick[slot] = tick;
        }
    }

    private void put(int itemKey, long seg, boolean fwd, float progress, float spd, long tick) {
        int slot = slotByItem.get(itemKey);
        if (slot < 0) {
            if (slotByItem.size() >= MAX_ITEMS) {
                return;
            }
            slot = freeCount > 0 ? freeSlots[--freeCount] : slotCount++;
            if (slot >= item.length) {
                int grown = item.length << 1;
                item = Arrays.copyOf(item, grown);
                segment = Arrays.copyOf(segment, grown);
                forward = Arrays.copyOf(forward, grown);
                startProgress = Arrays.copyOf(startProgress, grown);
                speed = Arrays.copyOf(speed, grown);
                entryTick = Arrays.copyOf(entryTick, grown);
                arrival = Arrays.copyOf(arrival, grown);
            }
            slotByItem.put(itemKey, slot);
            item[slot] = itemKey;
        }
        arrival[slot] = nextArrival++;
        segment[slot] = seg;
        forward[slot] = fwd;
        startProgress[slot] = progress;
        speed[slot] = spd;
        entryTick[slot] = tick;
    }
}
