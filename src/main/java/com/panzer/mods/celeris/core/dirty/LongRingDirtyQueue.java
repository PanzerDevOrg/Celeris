package com.panzer.mods.celeris.core.dirty;

import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link DirtyQueue} over {@link MpscRingBuffer} (already lock-free and
 * smoke-tested for cross-thread correctness) plus a growable presence
 * bitset consulted before every {@link #markDirty}, so a node already
 * pending can never be enqueued twice -- the "dedup by design" property
 * the contract promises. This reuses the ring's existing 8-byte fast path
 * ({@code tryPublish(long)}/{@code tryConsume()}) rather than writing a new
 * transport.
 *
 * <p>Multiple producer threads may call {@link #markDirty} concurrently,
 * matching {@link MpscRingBuffer}'s own contract; {@link #poll()} must be
 * called from a single consumer thread (the propagation solver's tick).
 *
 * <p>FIFO order is NOT guaranteed across the whole queue lifetime: the
 * presence bitset means a node that is marked dirty, drained, and marked
 * dirty again later is free to land in a different ring slot than its
 * first visit, and {@code TopologicalDAGSolver} re-orders drained indices
 * by signal level before evaluating them regardless (see its javadoc) --
 * so nothing in this design ever depends on strict publish order being
 * preserved.
 */
public final class LongRingDirtyQueue implements DirtyQueue {

    private static final int WORD_BITS = 32;
    private static final int DEFAULT_RING_CAPACITY_POW2 = MpscRingBuffer.DEFAULT_CAPACITY_POW2;

    private static final long EMPTY = -1L;

    private final MpscRingBuffer ring;
    private final ReentrantLock growthLock = new ReentrantLock();
    private volatile AtomicIntegerArray presenceWords = new AtomicIntegerArray(64);

    public LongRingDirtyQueue() {
        this(DEFAULT_RING_CAPACITY_POW2);
    }

    public LongRingDirtyQueue(int ringCapacityPow2) {
        this.ring = new MpscRingBuffer(ringCapacityPow2, Long.BYTES);
    }

    // Presence-bit CAS loops. Written out instead of getAndUpdate(lambda):
    // the lambda captures `bit` and allocates on every call, which sits
    // directly on the markDirty/poll hot path. Static + non-capturing.
    private static boolean setBit(AtomicIntegerArray words, int wordIndex, int bit) {
        for (;;) {
            int w = words.get(wordIndex);
            if ((w & bit) != 0) {
                return false;
            }
            if (words.compareAndSet(wordIndex, w, w | bit)) {
                return true;
            }
        }
    }

    private static void clearBit(AtomicIntegerArray words, int wordIndex, int bit) {
        for (;;) {
            int w = words.get(wordIndex);
            if ((w & bit) == 0 || words.compareAndSet(wordIndex, w, w & ~bit)) {
                return;
            }
        }
    }

    @Override
    public void markDirty(int denseIndex) {
        ensureCapacity(denseIndex);
        int wordIndex = denseIndex >>> 5;
        int bit = 1 << (denseIndex & (WORD_BITS - 1));

        AtomicIntegerArray words = presenceWords;
        if (!setBit(words, wordIndex, bit)) {
            return; // already pending
        }

        if (!ring.tryPublish(denseIndex)) {
            // Ring saturated (should not happen in practice -- the ring is
            // sized well above PropagationBudget.maxNodesPerPass, and a
            // single propagate() pass fully drains before the next batch
            // of external mutations can refill it). Revert the presence
            // bit so a future markDirty for the same index gets a fresh
            // chance to publish instead of being dropped silently.
            clearBit(words, wordIndex, bit);
        }
    }

    @Override
    public int poll() {
        long value = ring.tryConsume(EMPTY);
        if (value == EMPTY) {
            return -1;
        }
        int denseIndex = (int) value;
        clearBit(presenceWords, denseIndex >>> 5, 1 << (denseIndex & (WORD_BITS - 1)));
        return denseIndex;
    }

    @Override
    public boolean isIdle() {
        return !ring.hasNext();
    }

    /** Derived from ring cursors -- no separate counter to contend on per item. */
    @Override
    public int pendingCount() {
        return ring.size();
    }

    private void ensureCapacity(int denseIndex) {
        int requiredWords = denseIndex / WORD_BITS + 1;
        if (requiredWords <= presenceWords.length()) {
            return;
        }
        growthLock.lock();
        try {
            AtomicIntegerArray current = presenceWords;
            if (requiredWords <= current.length()) {
                return;
            }
            int newLength = Integer.highestOneBit(requiredWords - 1) << 1;
            AtomicIntegerArray grown = new AtomicIntegerArray(newLength);
            for (int i = 0; i < current.length(); i++) {
                grown.set(i, current.get(i));
            }
            presenceWords = grown;
        } finally {
            growthLock.unlock();
        }
    }
}
