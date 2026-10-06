package com.panzer.mods.celeris.graph.discrete;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Struct-of-arrays storage for discrete node state, indexed by the SAME
 * dense index {@code NetworkTopology} assigns ({@code denseIndexOf}) -- no
 * separate ID space, no translation table between "topology index" and
 * "discrete state index". One {@link MemoryBackend} allocation for the
 * whole field array, sized to NetworkTopology's current dense size and
 * grown geometrically on demand, exactly like {@code MpscRingBuffer}'s
 * capacity discipline.
 *
 * <p>Field layout, one packed byte per node (fits the whole per-node state
 * in a single cache line read across 64 nodes):
 * <pre>
 *   bits [0..3] signal level      (0-15, matches vanilla attenuation range)
 *   bit  [4]    is source          (never decays, e.g. a lever/block)
 *   bit  [5]    has deferred delay (repeater-style -- propagates next pass, not this one)
 *   bit  [6]    locked             (repeater locking semantics)
 *   bit  [7]    reserved
 * </pre>
 */
public final class DiscreteNodeStore implements AutoCloseable {

    private static final int SIGNAL_MASK = 0x0F;
    private static final int SOURCE_BIT = 1 << 4;
    private static final int DEFERRED_BIT = 1 << 5;
    private static final int LOCKED_BIT = 1 << 6;

    private final MemoryBackend backend;
    private long stateHandle;
    private int capacity;

    public DiscreteNodeStore(MemoryBackend backend, int initialCapacity) {
        this.backend = backend;
        this.capacity = Math.max(initialCapacity, 1);
        this.stateHandle = backend.allocate(this.capacity, 64L);
    }

    /** The raw packed state byte (0..255) -- one off-heap read for callers that need several fields. */
    public int packedState(int denseIndex) {
        return backend.getByte(stateHandle, denseIndex) & 0xFF;
    }

    public static int signalBits(int packed) {
        return packed & SIGNAL_MASK;
    }

    public static boolean isSourceBits(int packed) {
        return (packed & SOURCE_BIT) != 0;
    }

    /** Backend and handle of the state array, for off-heap vector passes ({@code VectorOperations.*Bytes}). */
    public MemoryBackend backend() {
        return backend;
    }

    public long handle() {
        return stateHandle;
    }

    public int capacity() {
        return capacity;
    }

    public int signalLevel(int denseIndex) {
        return backend.getByte(stateHandle, denseIndex) & SIGNAL_MASK;
    }

    public void setSignalLevel(int denseIndex, int level) {
        byte current = backend.getByte(stateHandle, denseIndex);
        byte updated = (byte) ((current & ~SIGNAL_MASK) | (level & SIGNAL_MASK));
        backend.setByte(stateHandle, denseIndex, updated);
    }

    public boolean isSource(int denseIndex) {
        return (backend.getByte(stateHandle, denseIndex) & SOURCE_BIT) != 0;
    }

    public void setSource(int denseIndex, boolean isSource) {
        setFlag(denseIndex, SOURCE_BIT, isSource);
    }

    public boolean hasDeferredDelay(int denseIndex) {
        return (backend.getByte(stateHandle, denseIndex) & DEFERRED_BIT) != 0;
    }

    public void setDeferredDelay(int denseIndex, boolean deferred) {
        setFlag(denseIndex, DEFERRED_BIT, deferred);
    }

    public boolean isLocked(int denseIndex) {
        return (backend.getByte(stateHandle, denseIndex) & LOCKED_BIT) != 0;
    }

    public void setLocked(int denseIndex, boolean locked) {
        setFlag(denseIndex, LOCKED_BIT, locked);
    }

    /** Resets a slot to all-zero state -- called when a dense index is freed/reused by NetworkTopology. */
    public void clear(int denseIndex) {
        backend.setByte(stateHandle, denseIndex, (byte) 0);
    }

    private void setFlag(int denseIndex, int bit, boolean value) {
        byte current = backend.getByte(stateHandle, denseIndex);
        byte updated = value ? (byte) (current | bit) : (byte) (current & ~bit);
        backend.setByte(stateHandle, denseIndex, updated);
    }

    /** Grows the backing allocation, called by DiscreteGraphImpl when NetworkTopology's dense size outgrows capacity. */
    void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= capacity) {
            return;
        }
        int newCapacity = Integer.highestOneBit(requiredCapacity - 1) << 1;
        long newHandle = backend.allocate(newCapacity, 64L); // zeroed by contract
        backend.copy(stateHandle, 0, newHandle, 0, capacity);
        backend.free(stateHandle); // eagerly reclaimed (Phase 1 backends)
        stateHandle = newHandle;
        capacity = newCapacity;
    }

    /** Zeroes every slot (bulk fill, not a per-node loop). */
    public void clearAll() {
        backend.fill(stateHandle, 0, capacity, (byte) 0);
    }

    @Override
    public void close() {
        backend.free(stateHandle);
    }
}
