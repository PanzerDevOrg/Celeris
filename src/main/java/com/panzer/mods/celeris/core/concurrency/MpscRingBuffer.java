package com.panzer.mods.celeris.core.concurrency;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.util.math.BranchlessTables;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Fixed-capacity, off-heap, lock-free multi-producer / single-consumer ring
 * buffer. Storage comes from whichever {@link MemoryBackend} {@link
 * CelerisRuntime} selected for this JVM -- FFM fast path or the pure-Java
 * compat fallback -- so this class never references {@code MemorySegment}
 * directly and works identically on either.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Multiple producer threads</b> may call {@link #tryPublish} concurrently.</li>
 *   <li><b>Exactly one consumer thread</b> may call {@link #tryConsume} or
 *       {@link #tryConsumeBatch} at a time. Calling either from more than one
 *       thread concurrently is undefined behavior -- {@code readCursor} is not
 *       CAS-guarded, only the write side is.</li>
 *   <li>{@code capacityPow2} must be a power of two; slots are padded to a
 *       full cache line each to avoid false sharing between producers.</li>
 *   <li>{@link #tryPublish} and {@link #tryConsume}/{@link #tryConsumeBatch}
 *       never block: a full ring makes {@code tryPublish} return {@code
 *       false} immediately, and an empty ring makes the consume methods
 *       return {@code false}/{@code 0} immediately. Callers own the retry
 *       policy (spin, backoff, park) -- this class intentionally has no
 *       opinion on it.</li>
 * </ul>
 *
 * <h2>Payload transport</h2>
 * Every real caller in this codebase transports slots of exactly {@code
 * Long.BYTES}, so the primary API works in terms of {@code long} values
 * directly ({@link #tryPublish(long)}, {@link #tryConsume()}) with no
 * caller-supplied memory at all. The {@code MemoryBackend}-handle overloads
 * ({@link #tryPublish(MemoryBackend, long, long)} etc.) remain for payloads
 * wider than 8 bytes.
 */
public final class MpscRingBuffer implements AutoCloseable {

    /** Slot stride alignment; {@code -Dceleris.ringbuffer.cacheline} (power of two, default 64). */
    private static final int CACHE_LINE_BYTES = readPow2Property("celeris.ringbuffer.cacheline", 64);
    /** Opt-in per-slot cache-line padding; {@code -Dceleris.ringbuffer.padSlots=true}. */
    private static final boolean PAD_SLOTS = Boolean.getBoolean("celeris.ringbuffer.padSlots");
    /** Default capacity as a power of two exponent; {@code -Dceleris.ringbuffer.capacity} (default 16). */
    public static final int DEFAULT_CAPACITY_POW2 = 1 << Integer.getInteger("celeris.ringbuffer.capacity", 16);

    @SuppressWarnings("SameParameterValue")
    private static int readPow2Property(String key, int fallback) {
        int v = Integer.getInteger(key, fallback);
        return BranchlessTables.isPowerOfTwo(v) && v >= 8 ? v : fallback;
    }

    private final MemoryBackend backend;
    private final long dataHandle;
    private final long stateHandle;
    private final int slotBytes;
    private final int slotStride;
    private final int capacity;
    private final long indexMask;

    // Fast path: when the payload is exactly 8 bytes (the common case for
    // tickets/handles/pointers), skip the byte-range copy entirely and go
    // through the backend's long accessor directly -- a single load/store
    // instead of a bounds-checked bulk copy.
    private final boolean longFastPath;

    private final PaddedCursor writeReservation;
    private final PaddedCursor readCursor;

    public MpscRingBuffer(int capacityPow2, int slotBytes) {
        this(CelerisRuntime.backend(), capacityPow2, slotBytes);
    }

    public MpscRingBuffer(MemoryBackend backend, int capacityPow2, int slotBytes) {
        if (!BranchlessTables.isPowerOfTwo(capacityPow2)) {
            throw new IllegalArgumentException("Celeris ring buffer capacity must be a power of two");
        }
        this.backend = backend;
        this.capacity = capacityPow2;
        this.indexMask = capacityPow2 - 1L;
        this.slotBytes = BranchlessTables.alignUp(slotBytes, 8);
        this.longFastPath = this.slotBytes == Long.BYTES;
        // Slots are packed densely by default. Padding each slot to a cache line
        // never actually removed producer false sharing: every publish also
        // writes the (unpadded, 8-per-line) state array, so the contended line
        // just moved there, while the data array cost 8x memory for 8-byte slots
        // and the consumer lost sequential prefetch. PAD_SLOTS re-enables it
        // for benchmarking.
        this.slotStride = PAD_SLOTS
                ? BranchlessTables.alignUp(this.slotBytes, CACHE_LINE_BYTES)
                : this.slotBytes;
        this.dataHandle = backend.allocate((long) this.slotStride * capacityPow2, CACHE_LINE_BYTES);
        this.stateHandle = backend.allocate((long) Long.BYTES * capacityPow2, CACHE_LINE_BYTES);

        // All-ones bytes == -1L in every state word; one bulk fill instead of
        // a per-slot store loop.
        backend.fill(stateHandle, 0, (long) Long.BYTES * capacityPow2, (byte) 0xFF);
        this.writeReservation = new PaddedCursor();
        this.readCursor = new PaddedCursor();
    }

    // State words MUST go through the backend's acquire/release accessors,
    // not plain getLong/setLong -- this happens-before relationship (a
    // producer's setStateRelease publishing its payload write, observed by
    // a consumer's getStateAcquire) is the entire correctness argument for
    // this being lock-free rather than merely unsynchronized. See
    // MemoryBackend#getLongAcquire.
    private long getStateAcquire(long slotIndex) {
        return backend.getLongAcquire(stateHandle, slotIndex * Long.BYTES);
    }

    @SuppressWarnings("SameParameterValue")
    private void setState(long slotIndex, long value) {
        backend.setLong(stateHandle, slotIndex * Long.BYTES, value);
    }

    private void setStateRelease(long slotIndex, long value) {
        backend.setLongRelease(stateHandle, slotIndex * Long.BYTES, value);
    }

    /** Fast path: publishes an 8-byte value directly, no backing memory needed from the caller. */
    public boolean tryPublish(long value) {
        return tryPublishIndexed(value) >= 0;
    }

    /** Same as {@link #tryPublish(long)}, returning the slot index instead of a flag. */
    public long tryPublishIndexed(long value) {
        long ticket = reserveSlot();
        if (ticket < 0) {
            return -1L;
        }
        long slotIndex = ticket & indexMask;
        backend.setLong(dataHandle, slotIndex * slotStride, value);
        setStateRelease(slotIndex, ticket);
        return slotIndex;
    }

    /** General path: publishes {@code length} bytes from {@code (srcHandle, srcOffset)}. */
    public boolean tryPublish(MemoryBackend srcBackend, long srcHandle, long srcOffset) {
        return tryPublishIndexed(srcBackend, srcHandle, srcOffset) >= 0;
    }

    /**
     * Same as {@link #tryPublish(MemoryBackend, long, long)}, but returns the slot index
     * the payload landed in instead of a plain success flag. Exists for callers that
     * need to correlate a published slot with out-of-band state keyed by
     * that same index -- e.g. {@code AsyncResultQueue}, which parks a Java
     * object reference in a side array at this index because the ring
     * itself can only carry raw bytes, not object references.
     * <p>
     * The index is only meaningful to the caller between the moment this
     * method returns it and the moment the consumer drains that slot (after
     * which the ring may reuse the index for a different, unrelated
     * publish). Callers relying on the index for anything beyond that
     * window are racing the consumer.
     * <p>
     * <b>Ordering note for callers who need to write side-channel state
     * (like {@code AsyncResultQueue}) keyed by the returned index:</b> by
     * the time this method returns, the slot's state word has already been
     * published via {@code setStateRelease} -- a concurrent consumer is
     * free to observe that publish and drain the slot immediately. Any
     * side-channel write keyed by the returned index (e.g. {@code
     * sideArray[index] = value}) that happens <i>after</i> this method
     * returns is NOT guaranteed to be visible to that consumer, and there
     * is no safe way to make it so after the fact. Callers with this need
     * must use {@link #reserveSlot} / {@link #publishReserved} instead,
     * which split reservation and publish into two steps so the
     * side-channel write can happen in between, before the release.
     *
     * @return the slot index in {@code [0, capacity)} the payload was
     *         written to, or {@code -1} if the ring was full
     */
    public long tryPublishIndexed(MemoryBackend srcBackend, long srcHandle, long srcOffset) {
        long ticket = reserveSlot();
        if (ticket < 0) {
            return -1L;
        }
        long slotIndex = ticket & indexMask;
        writePayload(srcBackend, srcHandle, srcOffset, slotIndex);
        setStateRelease(slotIndex, ticket);
        return slotIndex;
    }

    /**
     * First half of a split publish: reserves the next slot without
     * publishing it yet, so the caller can write side-channel state (keyed
     * by the returned index) before the slot becomes visible to the
     * consumer. Must be paired with exactly one {@link #publishReserved}
     * call for the returned ticket, or the ring deadlocks the consumer at
     * that index forever (the consumer will spin waiting for a publish
     * that never comes).
     *
     * @return the reservation ticket to pass to {@link #publishReserved},
     *         or {@code -1} if the ring was full
     */
    public long reserveSlot() {
        long ticket;
        do {
            ticket = writeReservation.getAcquire();
            if (ticket - readCursor.getAcquire() >= capacity) {
                return -1L;
            }
        } while (
                !writeReservation.compareAndSet(ticket, ticket + 1L)
        );
        return ticket;
    }

    /** The slot index a given {@link #reserveSlot} ticket maps to. */
    public long slotIndexFor(long reservationTicket) {
        return reservationTicket & indexMask;
    }

    /** Split-publish counterpart of {@link #tryPublish(long)} for the 8-byte fast path. */
    public void publishReserved(long reservationTicket, long value) {
        long slotIndex = reservationTicket & indexMask;
        backend.setLong(dataHandle, slotIndex * slotStride, value);
        setStateRelease(slotIndex, reservationTicket);
    }

    /**
     * Second half of a split publish: writes the payload and makes the
     * slot reserved by {@code reservationTicket} visible to the consumer.
     * Call this only after any side-channel writes keyed by {@link
     * #slotIndexFor}({@code reservationTicket}) have completed -- this
     * method's release-store is what makes those writes visible to the
     * consumer's matching acquire-load, exactly like {@link
     * #tryPublishIndexed(MemoryBackend, long, long)} does for its own internal payload write.
     */
    public void publishReserved(long reservationTicket, MemoryBackend srcBackend, long srcHandle, long srcOffset) {
        long slotIndex = reservationTicket & indexMask;
        writePayload(srcBackend, srcHandle, srcOffset, slotIndex);
        setStateRelease(slotIndex, reservationTicket);
    }

    private void writePayload(MemoryBackend srcBackend, long srcHandle, long srcOffset, long slotIndex) {
        transferPayload(srcBackend, srcHandle, srcOffset, backend, dataHandle, slotIndex * slotStride);
    }

    // Cross-backend copies (source/destination not on this ring's backend)
    // are the only path needing a heap bounce buffer. Producers are
    // multi-threaded, so the buffer is per-thread and sized once; never
    // touched on the 8-byte fast path or same-backend path.
    private ThreadLocal<byte[]> crossBackendScratch;

    private byte[] scratch() {
        ThreadLocal<byte[]> tl = crossBackendScratch;
        if (tl == null) {
            synchronized (this) {
                tl = crossBackendScratch;
                if (tl == null) {
                    final int n = slotBytes;
                    tl = ThreadLocal.withInitial(() -> new byte[n]);
                    crossBackendScratch = tl;
                }
            }
        }
        return tl.get();
    }

    /** Peeks the next slot index if published, without releasing it yet. */
    public long peekIndexedNoPayload() {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;
        long published = getStateAcquire(slotIndex);
        if (published != ticket) {
            return -1L;
        }
        return slotIndex;
    }

    /** Releases the slot previously returned by peekIndexedNoPayload, advancing the read cursor. */
    public void releaseConsumedNoPayload() {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;
        setState(slotIndex, -1L);
        readCursor.setRelease(ticket + 1L);
    }

    /**
     * Fast path: consumes an 8-byte value directly. Returns {@code
     * emptySentinel} if nothing was available -- pick a value that can
     * never be a legitimate payload (e.g. {@code -1L} for indices).
     * Allocation-free; prefer this over {@link #tryConsume()}.
     */
    public long tryConsume(long emptySentinel) {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;

        long published = getStateAcquire(slotIndex);
        if (published != ticket) {
            return emptySentinel;
        }

        long value = backend.getLong(dataHandle, slotIndex * slotStride);
        setState(slotIndex, -1L);
        readCursor.setRelease(ticket + 1L);
        return value;
    }

    /**
     * Boxing variant of {@link #tryConsume(long)}; returns {@code null} if
     * nothing was available. Allocates on every non-cached value.
     *
     * @deprecated use {@link #tryConsume(long)} with a sentinel, or
     *             {@link #tryConsumeBatch(long[], int, int)}.
     */
    @Deprecated
    public Long tryConsume() {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;
        if (getStateAcquire(slotIndex) != ticket) {
            return null;
        }
        long value = backend.getLong(dataHandle, slotIndex * slotStride);
        setState(slotIndex, -1L);
        readCursor.setRelease(ticket + 1L);
        return value;
    }

    /** True if at least one slot is published and ready for the consumer. */
    public boolean hasNext() {
        long ticket = readCursor.getPlain();
        return getStateAcquire(ticket & indexMask) == ticket;
    }

    /**
     * Approximate number of published-but-unconsumed slots (exact when no
     * producer is mid-publish). Single-consumer semantics apply.
     */
    public int size() {
        long n = writeReservation.getAcquire() - readCursor.getAcquire();
        return n < 0 ? 0 : (int) Math.min(n, capacity);
    }

    /**
     * Drains the next slot, if published, without reading its payload at
     * all -- for callers like {@code AsyncResultQueue} where the ring's
     * payload carries no information and the real data lives in a
     * side-channel array keyed by slot index. Skips the data-handle read
     * entirely, not just the caller-facing copy.
     *
     * @return the drained slot index, or {@code -1} if nothing was available
     */
    public long tryConsumeIndexedNoPayload() {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;

        long published = getStateAcquire(slotIndex);
        if (published != ticket) {
            return -1L;
        }

        setState(slotIndex, -1L);
        readCursor.setRelease(ticket + 1L);
        return slotIndex;
    }

    /** General path: consumes into {@code (dstHandle, dstOffset)}. */
    public boolean tryConsume(MemoryBackend dstBackend, long dstHandle, long dstOffset) {
        return tryConsumeIndexed(dstBackend, dstHandle, dstOffset) >= 0;
    }

    /**
     * Same as {@link #tryConsume(MemoryBackend, long, long)}, but returns the slot index that was
     * drained instead of a plain success flag. See {@link
     * #tryPublishIndexed(MemoryBackend, long, long)} for why this pairing exists.
     *
     * @return the slot index in {@code [0, capacity)} that was drained, or
     *         {@code -1} if nothing was available
     */
    public long tryConsumeIndexed(MemoryBackend dstBackend, long dstHandle, long dstOffset) {
        long ticket = readCursor.getPlain();
        long slotIndex = ticket & indexMask;

        long published = getStateAcquire(slotIndex);
        if (published != ticket) {
            return -1L;
        }

        readPayload(dstBackend, dstHandle, dstOffset, slotIndex);
        setState(slotIndex, -1L);
        readCursor.setRelease(ticket + 1L);
        return slotIndex;
    }

    private void readPayload(MemoryBackend dstBackend, long dstHandle, long dstOffset, long slotIndex) {
        transferPayload(backend, dataHandle, slotIndex * slotStride, dstBackend, dstHandle, dstOffset);
    }

    /**
     * Drains as many contiguously-published slots as are currently available,
     * up to {@code maxItems}, in a single call. Copies slot {@code i} into
     * {@code (dstHandle, dstOffset + i * slotStrideOut)}.
     * <p>
     * This exists because a single consumer thread racing 8-byte {@code
     * tryConsume} calls against multiple producers pays the full
     * getStateAcquire/copy/setState/setRelease sequence per item. Under
     * bursty multi-producer load the ring saturates and producers spin-wait
     * on {@code tryPublish}, burning cache bandwidth the consumer needs to
     * drain faster. Batching amortizes the per-item bookkeeping and the
     * readCursor release across the whole batch (one release instead of N),
     * letting the consumer keep pace with producers and shrinking the window
     * where producers spin against a full ring.
     *
     * @return number of items actually drained (0 if none were ready)
     */
    public int tryConsumeBatch(MemoryBackend dstBackend, long dstHandle, long dstOffset, long slotStrideOut, int maxItems) {
        return tryConsumeBatchIndexed(dstBackend, dstHandle, dstOffset, slotStrideOut, maxItems, null);
    }

    /**
     * Fast path: drains up to {@code maxItems} 8-byte values directly into a
     * plain {@code long[]}, starting at {@code dst[dstOffset]}. Same
     * early-stop-at-first-gap and single-release-per-batch behavior as the
     * {@link MemoryBackend}-based overload, with no backend/handle plumbing
     * required from the caller -- this is the overload most callers and
     * tests want.
     *
     * @return number of items actually drained (0 if none were ready)
     */
    public int tryConsumeBatch(long[] dst, int dstOffset, int maxItems) {
        long ticket = readCursor.getPlain();
        int drained = 0;

        while (drained < maxItems) {
            long slotIndex = (ticket + drained) & indexMask;
            long published = getStateAcquire(slotIndex);
            if (published != ticket + drained) {
                break;
            }
            dst[dstOffset + drained] = backend.getLong(dataHandle, slotIndex * slotStride);
            setState(slotIndex, -1L);
            drained++;
        }

        if (drained > 0) {
            readCursor.setRelease(ticket + drained);
        }
        return drained;
    }

    /**
     * Same as {@link #tryConsumeBatch}, but additionally reports the slot
     * index of each drained item via {@code indexSink}, in drain order. See
     * {@link #tryPublishIndexed(MemoryBackend, long, long)} for why this pairing exists. Pass {@code
     * null} for {@code indexSink} to skip index reporting entirely (that's
     * exactly what {@link #tryConsumeBatch} does).
     *
     * @param indexSink called once per drained item, in order, with its
     *                  slot index; may be {@code null}
     * @return number of items actually drained (0 if none were ready)
     */
    public int tryConsumeBatchIndexed(MemoryBackend dstBackend, long dstHandle, long dstOffset, long slotStrideOut,
                                       int maxItems, java.util.function.LongConsumer indexSink) {
        long ticket = readCursor.getPlain();
        int drained = 0;

        while (drained < maxItems) {
            long slotIndex = (ticket + drained) & indexMask;
            long published = getStateAcquire(slotIndex);
            if (published != ticket + drained) {
                break;
            }

            long outOffset = dstOffset + (long) drained * slotStrideOut;
            readPayload(dstBackend, dstHandle, outOffset, slotIndex);
            setState(slotIndex, -1L);
            if (indexSink != null) {
                indexSink.accept(slotIndex);
            }
            drained++;
        }

        if (drained > 0) {
            readCursor.setRelease(ticket + drained);
        }
        return drained;
    }

    private void transferPayload(MemoryBackend srcBackend, long srcHandle, long srcOffset,
                                 MemoryBackend dstBackend, long dstHandle, long dstOffset) {
        if (longFastPath) {
            long value = srcBackend.getLong(srcHandle, srcOffset);
            dstBackend.setLong(dstHandle, dstOffset, value);
        } else if (srcBackend == dstBackend) {
            srcBackend.copy(srcHandle, srcOffset, dstHandle, dstOffset, slotBytes);
        } else {
            byte[] scratch = scratch();
            srcBackend.copyToHeap(srcHandle, srcOffset, scratch, 0, slotBytes);
            dstBackend.copyFromHeap(dstHandle, dstOffset, scratch, 0, slotBytes);
        }
    }

    public int capacity() {
        return capacity;
    }

    public MemoryBackend backend() {
        return backend;
    }

    @Override
    public void close() {
        backend.free(dataHandle);
        backend.free(stateHandle);
    }

    // Class-hierarchy padding: HotSpot may reorder fields *within* a class (it
    // groups by size, so q*/value/p* in one class were not guaranteed to stay
    // in declaration order), but always lays out superclass fields before
    // subclass fields. Splitting the padding across three classes is the
    // layout-stable way (as in JCTools/Disruptor) to keep `value` alone on its
    // cache line without @Contended, which needs -XX:-RestrictContended.
    // The JIT never eliminates object fields, so no "keep alive" read is needed.
    @SuppressWarnings("unused")
    private static class CursorPadBefore {
        long q0, q1, q2, q3, q4, q5, q6, q7;
    }

    private static class CursorValue extends CursorPadBefore {
        volatile long value;
    }

    @SuppressWarnings("unused")
    private static final class PaddedCursor extends CursorValue {
        private static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(CursorValue.class, "value", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        long p0, p1, p2, p3, p4, p5, p6, p7;

        long getAcquire() {
            return (long) VALUE.getAcquire(this);
        }

        long getPlain() {
            return (long) VALUE.get(this);
        }

        boolean compareAndSet(long expected, long updated) {
            return VALUE.compareAndSet(this, expected, updated);
        }

        void setRelease(long v) {
            VALUE.setRelease(this, v);
        }
    }
}
