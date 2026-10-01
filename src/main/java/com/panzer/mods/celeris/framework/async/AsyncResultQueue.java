package com.panzer.mods.celeris.framework.async;

import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * Hands async work results from any number of worker threads back to a
 * single tick thread, without the tick thread ever blocking and without
 * allocating a queue node per result.
 *
 * <h2>Why this exists</h2>
 * A very common Minecraft-mod pattern: heavy work (I/O, pathfinding,
 * external computation, whatever) runs on a worker thread pool because it
 * cannot happen on the tick thread without stalling the game, but the
 * <i>result</i> of that work can only be applied to the world from the tick
 * thread. Most mods reach for {@code ConcurrentLinkedQueue<T>} plus a
 * drain-at-start-of-tick loop. That works, but it costs one heap allocation
 * per queued item (the linked-queue node) plus a CAS per {@code offer}/
 * {@code poll}. {@link AsyncResultQueue} is a drop-in replacement for that
 * exact pattern, backed by {@link MpscRingBuffer}: the ring only ever
 * transports an 8-byte slot index, never the result object itself, so
 * publishing a result costs one CAS and one long store, with the Java
 * object living in a fixed, pre-sized array instead of a fresh node.
 *
 * <h2>How results are stored</h2>
 * Result objects are ordinary Java objects and can't live in the ring
 * buffer's off-heap memory, so they're held in a fixed-size {@link
 * AtomicReferenceArray}, one slot per ring slot. {@link #offer} reserves a
 * slot via {@link MpscRingBuffer#reserveSlot}, writes the object into
 * {@code slots[index]}, <i>then</i> publishes via {@link
 * MpscRingBuffer#publishReserved(long, long)} -- see {@link #offer} for why
 * that ordering is the part that actually has to be correct.
 * <p>
 * The ring's own 8-byte payload carries no information here (the object
 * reference is the real payload, living in {@code slots}) -- {@code offer}
 * publishes a constant {@code 0L} and {@code drainTo} never reads it back.
 * This is exactly what the {@code long}-valued fast-path overloads on
 * {@link MpscRingBuffer} exist for: no scratch memory of any kind is needed
 * on either the producer or consumer side, on either backend.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Any number of worker threads may call {@link #offer} concurrently.</li>
 *   <li>{@link #drainTo} must only ever be called from a single thread --
 *       normally the tick thread, once per tick, at the start of tick
 *       processing. Same single-consumer contract as {@link MpscRingBuffer}
 *       itself.</li>
 *   <li>{@link #offer} never blocks. If the ring is full (the tick thread
 *       is falling behind), it returns {@code false} immediately and the
 *       caller decides what to do (drop, log, retry with backoff -- this
 *       class has no opinion, same as the underlying ring).</li>
 *   <li>{@code capacityPow2} must be a power of two, same requirement as
 *       {@link MpscRingBuffer}.</li>
 *   <li>{@code null} results are rejected -- {@code null} is used
 *       internally to mark a drained slot.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // module init, once:
 * AsyncResultQueue<PathResult> pathResults = new AsyncResultQueue<>(1 << 10);
 *
 * // worker thread, whenever a background pathfind finishes:
 * pathResults.offer(result); // true if accepted, false if the queue is full
 *
 * // server tick, once per tick, on the tick thread:
 * pathResults.drainTo(result -> applyPathToEntity(result), 64);
 * }</pre>
 */
public final class AsyncResultQueue<T> implements AutoCloseable {

    private final MpscRingBuffer ring;
    private final AtomicReferenceArray<T> slots;
    private final int capacity;

    public AsyncResultQueue(int capacityPow2) {
        this.ring = new MpscRingBuffer(capacityPow2, Long.BYTES);
        this.slots = new AtomicReferenceArray<>(capacityPow2);
        this.capacity = capacityPow2;
    }

    /**
     * Offers a result from any worker thread. Never blocks.
     * <p>
     * Uses {@link MpscRingBuffer#reserveSlot}/{@link
     * MpscRingBuffer#publishReserved(long, long)} (the split-publish API)
     * rather than {@link MpscRingBuffer#tryPublishIndexed(long)}
     * deliberately: {@code tryPublishIndexed} performs its release-store --
     * making the slot visible to the consumer -- before returning the
     * index, which would leave no safe window to write {@code
     * slots[index]} beforehand. The split API reserves the slot first,
     * lets us write the Java object reference into {@code slots[index]},
     * and only then publishes -- so the object write happens-before the
     * release the consumer's {@code drainTo} synchronizes with, exactly
     * the ordering this class needs.
     *
     * @return {@code true} if accepted, {@code false} if the queue is
     *         currently full (the consumer is falling behind) -- the caller
     *         decides whether to drop, log, or retry.
     */
    public boolean offer(T result) {
        if (result == null) {
            throw new NullPointerException("Celeris AsyncResultQueue does not accept null results");
        }

        long ticket = ring.reserveSlot();
        if (ticket < 0) {
            return false;
        }
        int index = (int) ring.slotIndexFor(ticket);

        // Happens-before the publish below, which is exactly what makes
        // this write visible to the consumer's getStateAcquire in drainTo.
        slots.set(index, result);

        // The ring's payload for this slot is unused by AsyncResultQueue --
        // the real payload is the object reference in `slots`, not this
        // value -- but publishReserved needs *a* long to write. 0L is
        // arbitrary and never read back.
        ring.publishReserved(ticket, 0L);
        return true;
    }

    /**
     * Drains up to {@code maxItems} results, calling {@code consumer} for
     * each one in publish order. Must only be called from a single thread.
     * Never blocks -- returns immediately once the ring has no more
     * published-but-undrained results, even if that's zero.
     *
     * @return number of results actually drained
     */
    public int drainTo(Consumer<T> consumer, int maxItems) {
        int clamped = Math.min(maxItems, capacity);
        int drained = 0;

        while (drained < clamped) {
            long slotIndex = ring.peekIndexedNoPayload();
            if (slotIndex < 0) break;
            int idx = (int) slotIndex;
            // Safe without a null-check-and-retry: offer() writes
            // slots[idx] via reserveSlot()/publishReserved(), and
            // publishReserved's release-store is exactly what this
            // getStateAcquire (inside tryConsumeIndexedNoPayload) synchronizes
            // with. The object write happens-before the release, so it is
            // guaranteed visible here. If this ever observes null, that
            // means offer() was changed to publish before writing --
            // see offer()'s javadoc for why that ordering must not happen.
            T result = slots.getAndSet(idx, null);
            ring.releaseConsumedNoPayload();
            consumer.accept(result);
            drained++;
        }
        return drained;
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void close() {
        ring.close();
    }
}
