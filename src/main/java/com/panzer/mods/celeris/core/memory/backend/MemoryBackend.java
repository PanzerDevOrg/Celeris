package com.panzer.mods.celeris.core.memory.backend;

/**
 * Abstracts the off-heap storage primitive Celeris builds on, so the rest of
 * the engine (MemoryBus, PacketPipeline, MpscRingBuffer, FrameHeader) never
 * references {@code java.lang.foreign.MemorySegment} directly and can run on
 * either backend transparently.
 *
 * <p>Allocations are identified by an opaque {@code long} handle rather than
 * a shared segment type -- this keeps every accessor a flat array-index +
 * primitive-op sequence for both implementations, with no boxing and no
 * virtual dispatch through a common "segment" object on the hot path
 * (publish/consume in {@code MpscRingBuffer} calls these every item).
 *
 * <p>Implementations are NOT required to be thread-safe for allocate/free;
 * callers (MemoryBus etc.) already serialize those through their own
 * concurrent structures. Read/write accessors on an already-allocated handle
 * follow whatever concurrency contract the caller establishes (e.g.
 * MpscRingBuffer's acquire/release discipline) -- the backend itself adds no
 * additional synchronization.
 */
public interface MemoryBackend extends AutoCloseable {

    /** Allocates a zeroed region of at least {@code byteSize} bytes, aligned to {@code alignment}. */
    long allocate(long byteSize, long alignment);

    /**
     * Releases the allocation behind {@code handle}. Memory is reclaimed
     * eagerly and the handle value may be reused by a later
     * {@link #allocate}; any access through a freed handle is a caller bug
     * (FFM backend: fails fast with {@code IllegalStateException}).
     */
    void free(long handle);

    byte getByte(long handle, long offset);

    void setByte(long handle, long offset, byte value);

    int getInt(long handle, long offset);

    void setInt(long handle, long offset, int value);

    long getLong(long handle, long offset);

    void setLong(long handle, long offset, long value);

    /**
     * Volatile/acquire read of a long -- establishes happens-before with a
     * matching {@link #setLongRelease}. Required for cross-thread
     * publish/consume protocols (e.g. {@code MpscRingBuffer}'s state
     * words); plain {@link #getLong}/{@link #setLong} give no such
     * guarantee and must not be used for that purpose.
     */
    long getLongAcquire(long handle, long offset);

    /** Volatile/release write of a long -- see {@link #getLongAcquire}. */
    void setLongRelease(long handle, long offset, long value);

    //* Accessors (default-implemented so third-party backends keep
    //* compiling). Both shipped backends override every one of these with a
    //* direct VarHandle / MemorySegment path; the defaults are correct but
    //* compose from the primitive accessors above.

    /** Acquire read of an int -- same contract as {@link #getLongAcquire}. */
    default int getIntAcquire(long handle, long offset) {
        int v = getInt(handle, offset);
        java.lang.invoke.VarHandle.acquireFence();
        return v;
    }

    /** Release write of an int -- same contract as {@link #setLongRelease}. */
    default void setIntRelease(long handle, long offset, int value) {
        java.lang.invoke.VarHandle.releaseFence();
        setInt(handle, offset, value);
    }

    default short getShort(long handle, long offset) {
        // Little-endian, matching JAVA_SHORT_UNALIGNED on every target platform.
        return (short) ((getByte(handle, offset) & 0xFF) | ((getByte(handle, offset + 1) & 0xFF) << 8));
    }

    default void setShort(long handle, long offset, short value) {
        setByte(handle, offset, (byte) value);
        setByte(handle, offset + 1, (byte) (value >>> 8));
    }

    default float getFloat(long handle, long offset) {
        return Float.intBitsToFloat(getInt(handle, offset));
    }

    default void setFloat(long handle, long offset, float value) {
        setInt(handle, offset, Float.floatToRawIntBits(value));
    }

    /** Sets {@code length} bytes starting at {@code offset} to {@code value}. */
    default void fill(long handle, long offset, long length, byte value) {
        for (long i = 0; i < length; i++) {
            setByte(handle, offset + i, value);
        }
    }

    /**
     * Raw native view of the allocation, if this backend has one: the FFM
     * backend returns its {@code java.lang.foreign.MemorySegment}; others
     * return {@code null}. Typed as {@code Object} so {@code main} never
     * names an FFM type -- callers (the SIMD lane bridge) cast inside their
     * own preview-compiled module.
     */
    default Object nativeSegment(long handle) {
        return null;
    }

    void copyFromHeap(long handle, long dstOffset, byte[] src, int srcOffset, int length);

    void copyToHeap(long handle, long srcOffset, byte[] dst, int dstOffset, int length);

    /** Bulk copy between two allocations on this same backend. */
    void copy(long srcHandle, long srcOffset, long dstHandle, long dstOffset, long length);

    /** Human-readable name for logging/diagnostics (e.g. "FFM", "Heap (compat mode)"). */
    String name();

    @Override
    void close();
}
