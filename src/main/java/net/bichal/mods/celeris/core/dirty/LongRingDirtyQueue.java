package net.bichal.mods.celeris.core.dirty;

import net.bichal.mods.celeris.core.concurrency.MpscRingBuffer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>The presence bitset is made of fixed chunks that are never copied or
 * replaced once installed, so growing it cannot lose a bit that a concurrent
 * {@link #poll()} clears. An index published while the ring is full goes to an
 * overflow list instead of being dropped. The ring is off-heap: {@link #close()}
 * frees it.
 */
public final class LongRingDirtyQueue implements DirtyQueue, AutoCloseable {

    private static final int WORD_BITS = 32;
    private static final int CHUNK_WORDS = 1024;
    private static final int CHUNK_BITS = CHUNK_WORDS * WORD_BITS;
    private static final int DEFAULT_RING_CAPACITY_POW2 = MpscRingBuffer.DEFAULT_CAPACITY_POW2;

    private static final long EMPTY = -1L;

    private final MpscRingBuffer ring;
    private final ReentrantLock growthLock = new ReentrantLock();
    /** Chunks are installed once, under the lock, and shared by every later copy of this array. */
    private volatile AtomicIntegerArray[] chunks = new AtomicIntegerArray[1];
    private final ConcurrentLinkedQueue<Integer> overflow = new ConcurrentLinkedQueue<>();
    private final AtomicInteger overflowSize = new AtomicInteger();

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

    private AtomicIntegerArray chunk(int denseIndex) {
        int c = denseIndex / CHUNK_BITS;
        AtomicIntegerArray[] table = chunks;
        if (c < table.length) {
            AtomicIntegerArray found = table[c];
            if (found != null) {
                return found;
            }
        }
        return installChunk(c);
    }

    private AtomicIntegerArray installChunk(int c) {
        growthLock.lock();
        try {
            AtomicIntegerArray[] table = chunks;
            if (c >= table.length) {
                table = java.util.Arrays.copyOf(table, Math.max(c + 1, table.length * 2));
                chunks = table;
            }
            AtomicIntegerArray found = table[c];
            if (found == null) {
                found = new AtomicIntegerArray(CHUNK_WORDS);
                table[c] = found;
            }
            return found;
        } finally {
            growthLock.unlock();
        }
    }

    @Override
    public void markDirty(int denseIndex) {
        if (denseIndex < 0) {
            throw new IllegalArgumentException("denseIndex < 0: " + denseIndex);
        }
        AtomicIntegerArray words = chunk(denseIndex);
        int wordIndex = (denseIndex % CHUNK_BITS) >>> 5;
        int bit = 1 << (denseIndex & (WORD_BITS - 1));

        if (!setBit(words, wordIndex, bit)) {
            return; // already pending
        }

        if (!ring.tryPublish(denseIndex)) {
            // Ring saturated: keep the update on the overflow list rather than
            // dropping it. poll() drains the ring first, then this list.
            overflowSize.incrementAndGet();
            overflow.add(denseIndex);
        }
    }

    @Override
    public int poll() {
        long value = ring.tryConsume(EMPTY);
        int denseIndex;
        if (value != EMPTY) {
            denseIndex = (int) value;
        } else {
            Integer spilled = overflow.poll();
            if (spilled == null) {
                return -1;
            }
            overflowSize.decrementAndGet();
            denseIndex = spilled;
        }
        clearBit(chunk(denseIndex), (denseIndex % CHUNK_BITS) >>> 5, 1 << (denseIndex & (WORD_BITS - 1)));
        return denseIndex;
    }

    @Override
    public boolean isIdle() {
        return !ring.hasNext() && overflow.isEmpty();
    }

    /** Derived from ring cursors and the overflow count -- no separate counter to contend on per item. */
    @Override
    public int pendingCount() {
        return ring.size() + overflowSize.get();
    }

    /** Frees the off-heap ring. The queue must not be used afterwards. */
    @Override
    public void close() {
        ring.close();
    }
}
