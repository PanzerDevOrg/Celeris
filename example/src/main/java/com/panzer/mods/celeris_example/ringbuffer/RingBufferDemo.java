package com.panzer.mods.celeris_example.ringbuffer;


import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;
import com.panzer.mods.celeris.core.memory.MemoryBus;
import com.panzer.mods.celeris.framework.async.AsyncResultQueue;
import com.panzer.mods.celeris_example.memorybank.MemoryBankBlockEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real, working demo of {@link MpscRingBuffer} used directly -- raw
 * {@code long} values, no wrapper object, no {@link AsyncResultQueue} in between.
 * {@link #fireBurst} publishes from {@value #PRODUCER_COUNT} genuinely
 * separate threads at once (the actual multi-producer case this class is
 * built for), and {@link #drainTick} -- called from exactly one thread,
 * the server tick thread -- is the single consumer the "MPSC" contract
 * requires. Calling drain from more than one thread is undefined behavior
 * for this class; that's the trade this buffer makes for its speed.
 *
 * <p>Unlike {@link MemoryBus}, {@link
 * MpscRingBuffer#close()} is safe to call directly here -- it only frees
 * this ring's own two allocations, not the shared backend.
 * See {@link MemoryBankBlockEntity}'s javadoc for the contrasting
 * case where that isn't true.
 */
public final class RingBufferDemo {

    private static final int RING_CAPACITY = 1 << 8;
    private static final int PRODUCER_COUNT = 8;
    private static final int MAX_DRAIN_PER_TICK = 64;

    private static final MpscRingBuffer RING = new MpscRingBuffer(RING_CAPACITY, Long.BYTES);
    private static final ExecutorService PRODUCERS = Executors.newFixedThreadPool(PRODUCER_COUNT);
    private static final AtomicLong RUNNING_TOTAL = new AtomicLong();
    private static final long[] DRAIN_SCRATCH = new long[MAX_DRAIN_PER_TICK];

    private RingBufferDemo() {
    }

    /** Submits {@value #PRODUCER_COUNT} publishes to a real thread pool -- these genuinely race with each other and with {@link #drainTick}. */
    public static void fireBurst() {
        for (int i = 1; i <= PRODUCER_COUNT; i++) {
            long value = i;
            PRODUCERS.submit(() -> {
                if (!RING.tryPublish(value)) {
                    // Ring full -- the tick-thread consumer is falling behind
                    // the producers. This demo just drops it; a real mod
                    // would log once (not per-drop) or apply backpressure.
                }
            });
        }
    }

    /** Call once per server tick, from the tick thread only. Returns the sum drained this call, for display. */
    public static long drainTick() {
        int drained = RING.tryConsumeBatch(DRAIN_SCRATCH, 0, MAX_DRAIN_PER_TICK);
        long sum = 0L;
        for (int i = 0; i < drained; i++) {
            sum += DRAIN_SCRATCH[i];
        }
        if (drained > 0) {
            RUNNING_TOTAL.addAndGet(sum);
        }
        return sum;
    }

    public static long runningTotal() {
        return RUNNING_TOTAL.get();
    }

    /** Called from {@link RingBufferDemoEvents} on server stop -- shuts down the producer pool and frees the ring's off-heap allocation. */
    public static void shutdown() {
        PRODUCERS.shutdown();
        RING.close();
    }
}
