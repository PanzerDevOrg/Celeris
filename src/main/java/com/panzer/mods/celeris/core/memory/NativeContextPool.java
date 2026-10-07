package com.panzer.mods.celeris.core.memory;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Lock-free, bounded pool of native context pointers (zstd {@code ZSTD_CCtx} /
 * {@code ZSTD_DCtx}). Creating a context allocates and initializes its
 * workspace, which costs as much as compressing a small payload; reusing one
 * skips that entirely.
 *
 * <p>Each slot sits on its own cache line and every thread starts probing at
 * a slot derived from its id, so a thread normally gets back the context it
 * returned last time with one uncontended CAS, and threads never share a
 * cache line on the fast path. When every slot is empty a new context is
 * created; when every slot is full the returned one is freed, so the pool
 * holds at most {@code capacity} idle contexts. Taking a pointer out of a slot
 * by CAS makes its holder the only owner until it is released.
 *
 * <p>Pooled contexts live until the JVM exits (unless {@link #clear()} is
 * called): a bounded handful of native allocations, reclaimed by the OS.
 */
final class NativeContextPool {

    /** Longs per 64-byte cache line: one slot per line. */
    private static final int SLOT_STRIDE = 8;

    private final String what;
    private final AtomicLongArray slots;
    private final int mask;
    private final LongSupplier factory;
    private final LongConsumer destructor;

    NativeContextPool(String what, int capacity, LongSupplier factory, LongConsumer destructor) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        int size = capacity == 1 ? 1 : Integer.highestOneBit(capacity - 1) << 1;
        this.what = what;
        this.slots = new AtomicLongArray(size * SLOT_STRIDE);
        this.mask = size - 1;
        this.factory = factory;
        this.destructor = destructor;
    }

    /** One idle context per hardware thread, within [4, 16]. */
    static int defaultCapacity() {
        return Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
    }

    /** Takes a pooled context, or creates one. The caller owns it until {@link #release}. */
    long acquire() {
        int home = home();
        for (int i = 0; i <= mask; i++) {
            int index = ((home + i) & mask) * SLOT_STRIDE;
            long ctx = slots.get(index);
            if (ctx != 0L && slots.compareAndSet(index, ctx, 0L)) {
                return ctx;
            }
        }
        long created = factory.getAsLong();
        if (created == 0L) {
            throw new OutOfMemoryError("Celeris: native " + what + " allocation failed");
        }
        return created;
    }

    /** Returns a context taken from {@link #acquire}; frees it if the pool is full. */
    void release(long ctx) {
        int home = home();
        for (int i = 0; i <= mask; i++) {
            int index = ((home + i) & mask) * SLOT_STRIDE;
            if (slots.get(index) == 0L && slots.compareAndSet(index, 0L, ctx)) {
                return;
            }
        }
        destructor.accept(ctx);
    }

    /** Frees every idle context. Contexts currently held by callers are unaffected. */
    void clear() {
        for (int i = 0; i <= mask; i++) {
            long ctx = slots.getAndSet(i * SLOT_STRIDE, 0L);
            if (ctx != 0L) {
                destructor.accept(ctx);
            }
        }
    }

    /** Number of slots (a power of two, at least the requested capacity). */
    int capacity() {
        return mask + 1;
    }

    private static int home() {
        // Fibonacci hashing spreads sequential thread ids across slots.
        return (int) ((Thread.currentThread().threadId() * 0x9E3779B97F4A7C15L) >>> 40);
    }
}
