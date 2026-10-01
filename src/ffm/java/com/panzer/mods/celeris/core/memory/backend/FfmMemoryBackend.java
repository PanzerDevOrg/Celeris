package com.panzer.mods.celeris.core.memory.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Fast path: backs every handle with a real {@link MemorySegment}, each in
 * its own shared {@link Arena} so {@link #free} reclaims memory immediately.
 * Only constructed after {@code CelerisRuntime} has confirmed FFM is
 * actually usable on this JVM -- this class assumes {@code java.lang.foreign}
 * is available and makes no attempt to degrade gracefully itself.
 *
 * <p>Accessors are lock-free: one volatile array read plus an acquire
 * element read to resolve the handle, then a direct {@code MemorySegment}
 * access. Only allocate/free/close synchronize.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class FfmMemoryBackend implements MemoryBackend {

    private static final int INITIAL_CAPACITY = 64;
    // Aligned layouts (not the _UNALIGNED variants used elsewhere) specifically
    // because VarHandle.getAcquire/setRelease require natural alignment;
    // callers needing acquire/release semantics (MpscRingBuffer's state
    // words) always allocate/index those offsets at natural alignment.
    //? >=25 {
    /*private static final VarHandle LONG_ACQUIRE_RELEASE_HANDLE =
            ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle INT_ACQUIRE_RELEASE_HANDLE =
            ValueLayout.JAVA_INT.varHandle();*/
    //?} else {
    private static final VarHandle LONG_ACQUIRE_RELEASE_HANDLE =
            MethodHandles.memorySegmentViewVarHandle(ValueLayout.JAVA_LONG);
    private static final VarHandle INT_ACQUIRE_RELEASE_HANDLE =
            MethodHandles.memorySegmentViewVarHandle(ValueLayout.JAVA_INT);
    //?}

    // Handle table. Handles are indices into `segments`; each handle owns its
    // own shared Arena (parallel `arenas` array) so free() reclaims memory
    // eagerly and any late access through a freed handle fails fast with
    // IllegalStateException instead of silently reading dead memory.
    //
    // Readers never lock: `segments` is a volatile reference to an array that
    // is only ever published whole (copy-on-grow). Element stores go through
    // a release-mode VarHandle so a reader that observes the new slot value
    // also observes the fully-constructed segment. allocate/free are rare and
    // serialize on `tableLock`; they never block readers.
    //
    // Handles are generation-tagged: (generation << 32) | index. A slot's
    // generation is bumped on free(), so a stale handle whose index has been
    // reused by a later allocate() fails fast instead of silently aliasing
    // someone else's memory. Generations stay in [1, 2^31) so handles are
    // always positive (callers may use negative values as sentinels).
    private static final VarHandle SEGMENT_ELEMENT =
            MethodHandles.arrayElementVarHandle(MemorySegment[].class);

    /** Segments and generations published together, so one volatile read sees a consistent pair. */
    private record Table(MemorySegment[] segments, int[] generations) {
    }

    private volatile Table table = new Table(new MemorySegment[INITIAL_CAPACITY], new int[INITIAL_CAPACITY]);
    private Arena[] arenas = new Arena[INITIAL_CAPACITY];
    private int[] freeList = new int[INITIAL_CAPACITY];
    private int freeCount;
    private int nextHandle;
    private final Object tableLock = new Object();
    private volatile boolean closed;

    public FfmMemoryBackend() {
    }

    @Override
    public long allocate(long byteSize, long alignment) {
        Arena arena = Arena.ofShared();
        MemorySegment segment;
        try {
            segment = arena.allocate(byteSize, alignment);
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        synchronized (tableLock) {
            if (closed) {
                arena.close();
                throw new IllegalStateException("FfmMemoryBackend is closed");
            }
            int index = freeCount > 0 ? freeList[--freeCount] : nextHandle++;
            Table t = table;
            if (index >= t.segments().length) {
                int newLen = t.segments().length << 1;
                MemorySegment[] grownSegments = java.util.Arrays.copyOf(t.segments(), newLen);
                int[] grownGenerations = java.util.Arrays.copyOf(t.generations(), newLen);
                arenas = java.util.Arrays.copyOf(arenas, newLen);
                t = new Table(grownSegments, grownGenerations);
                t.generations()[index] = nextGeneration(t.generations()[index]);
                t.segments()[index] = segment;
                arenas[index] = arena;
                table = t; // volatile publish of the whole table
            } else {
                arenas[index] = arena;
                t.generations()[index] = nextGeneration(t.generations()[index]);
                // Release store orders the generation write before the segment becomes visible.
                SEGMENT_ELEMENT.setRelease(t.segments(), index, segment);
            }
            return ((long) t.generations()[index] << 32) | index;
        }
    }

    @Override
    public void free(long handle) {
        int h = (int) handle;
        Arena arena;
        synchronized (tableLock) {
            Table t = table;
            if (h < 0 || h >= arenas.length || t.generations()[h] != (int) (handle >>> 32)) {
                return; // stale, already freed, or never allocated -- idempotent
            }
            arena = arenas[h];
            if (arena == null) {
                return;
            }
            arenas[h] = null;
            SEGMENT_ELEMENT.setRelease(t.segments(), h, null);
            if (freeCount == freeList.length) {
                freeList = java.util.Arrays.copyOf(freeList, freeList.length << 1);
            }
            freeList[freeCount++] = h;
        }
        // Closing a shared arena waits for in-flight accesses on other threads
        // to drain and then unmaps -- done outside the table lock so allocate()
        // on other threads is not stalled by it.
        arena.close();
    }

    /**
     * Raw segment behind {@code handle} (used by {@code ZstdCompressionCodec}
     * for native downcalls). Lock-free: one volatile read of the table, then
     * plain element reads. A plain read is safe here: MemorySegment's state
     * is final-field-published, and observing a just-freed segment only
     * reaches its closed shared arena, which throws IllegalStateException.
     */
    public MemorySegment segmentOf(long handle) {
        Table t = table;
        int index = (int) handle;
        if (index >= 0 && index < t.segments().length) {
            MemorySegment s = t.segments()[index];
            if (s != null && t.generations()[index] == (int) (handle >>> 32)) {
                return s;
            }
        }
        // Covers freed, reused (stale generation), out-of-range, and post-close() handles.
        throw new IllegalStateException("Celeris: access through freed/stale/unknown handle 0x"
                + Long.toHexString(handle));
    }

    /** Next generation in [1, 2^31): never 0 (never-allocated), never negative. */
    private static int nextGeneration(int current) {
        return current == Integer.MAX_VALUE ? 1 : current + 1;
    }

    @Override
    public Object nativeSegment(long handle) {
        return segmentOf(handle);
    }

    @Override
    public byte getByte(long handle, long offset) {
        return segmentOf(handle).get(ValueLayout.JAVA_BYTE, offset);
    }

    @Override
    public void setByte(long handle, long offset, byte value) {
        segmentOf(handle).set(ValueLayout.JAVA_BYTE, offset, value);
    }

    @Override
    public int getInt(long handle, long offset) {
        return segmentOf(handle).get(ValueLayout.JAVA_INT_UNALIGNED, offset);
    }

    @Override
    public void setInt(long handle, long offset, int value) {
        segmentOf(handle).set(ValueLayout.JAVA_INT_UNALIGNED, offset, value);
    }

    @Override
    public long getLong(long handle, long offset) {
        return segmentOf(handle).get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }

    @Override
    public void setLong(long handle, long offset, long value) {
        segmentOf(handle).set(ValueLayout.JAVA_LONG_UNALIGNED, offset, value);
    }

    @Override
    public long getLongAcquire(long handle, long offset) {
        // JAVA_LONG_UNALIGNED has no built-in acquire/release variant on
        // MemorySegment directly, the aligned layout does. Slot state
        // words are always allocated at Long.BYTES-aligned offsets by
        // MpscRingBuffer, so the alignment requirement is already
        // satisfied in practice -- this uses the aligned, VarHandle-backed
        // access path specifically to get real acquire semantics.
        return (long) LONG_ACQUIRE_RELEASE_HANDLE.getAcquire(segmentOf(handle), offset);
    }

    @Override
    public void setLongRelease(long handle, long offset, long value) {
        LONG_ACQUIRE_RELEASE_HANDLE.setRelease(segmentOf(handle), offset, value);
    }

    @Override
    public int getIntAcquire(long handle, long offset) {
        return (int) INT_ACQUIRE_RELEASE_HANDLE.getAcquire(segmentOf(handle), offset);
    }

    @Override
    public void setIntRelease(long handle, long offset, int value) {
        INT_ACQUIRE_RELEASE_HANDLE.setRelease(segmentOf(handle), offset, value);
    }

    @Override
    public short getShort(long handle, long offset) {
        return segmentOf(handle).get(ValueLayout.JAVA_SHORT_UNALIGNED, offset);
    }

    @Override
    public void setShort(long handle, long offset, short value) {
        segmentOf(handle).set(ValueLayout.JAVA_SHORT_UNALIGNED, offset, value);
    }

    @Override
    public float getFloat(long handle, long offset) {
        return segmentOf(handle).get(ValueLayout.JAVA_FLOAT_UNALIGNED, offset);
    }

    @Override
    public void setFloat(long handle, long offset, float value) {
        segmentOf(handle).set(ValueLayout.JAVA_FLOAT_UNALIGNED, offset, value);
    }

    @Override
    public void fill(long handle, long offset, long length, byte value) {
        segmentOf(handle).asSlice(offset, length).fill(value);
    }

    @Override
    public void copyFromHeap(long handle, long dstOffset, byte[] src, int srcOffset, int length) {
        MemorySegment.copy(src, srcOffset, segmentOf(handle), ValueLayout.JAVA_BYTE, dstOffset, length);
    }

    @Override
    public void copyToHeap(long handle, long srcOffset, byte[] dst, int dstOffset, int length) {
        MemorySegment.copy(segmentOf(handle), ValueLayout.JAVA_BYTE, srcOffset, dst, dstOffset, length);
    }

    @Override
    public void copy(long srcHandle, long srcOffset, long dstHandle, long dstOffset, long length) {
        MemorySegment.copy(segmentOf(srcHandle), srcOffset, segmentOf(dstHandle), dstOffset, length);
    }

    @Override
    public String name() {
        return "FFM";
    }

    @Override
    public void close() {
        Arena[] toClose;
        synchronized (tableLock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = arenas;
            arenas = new Arena[0];
            table = new Table(new MemorySegment[0], new int[0]);
            freeCount = 0;
            nextHandle = 0;
        }
        for (Arena a : toClose) {
            if (a != null) {
                a.close();
            }
        }
    }
}
