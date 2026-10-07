package com.panzer.mods.celeris.core.memory.backend;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Off-heap backend on {@code sun.misc.Unsafe}: the full-speed path on Java 21,
 * where FFM is preview-only and therefore unavailable without
 * {@code --enable-preview}. Needs no launch flags ({@code jdk.unsupported} is
 * resolved by default). Selected after FFM and before the heap fallback; see
 * {@link CelerisRuntime}.
 *
 * <p>Safety model, matching {@code FfmMemoryBackend} where Unsafe allows it:
 * <ul>
 *   <li>Handles are generation-tagged ({@code (generation << 32) | index}), so a
 *       freed or reused handle throws instead of aliasing other memory.</li>
 *   <li>Every access is bounds-checked against the allocation size, so a bad
 *       offset throws {@link IndexOutOfBoundsException} instead of corrupting
 *       the heap or crashing the JVM. One compare per access; hoisted by C2
 *       out of counted loops.</li>
 *   <li>Unlike FFM's shared arenas, Unsafe cannot detect a free() racing with an
 *       access on another thread. Freeing a handle that other threads may still
 *       use is a caller bug here, exactly as with native {@code free}.</li>
 * </ul>
 *
 * <p>Byte order is native (little-endian on every supported target), matching
 * the {@code *_UNALIGNED} layouts the FFM backend uses.
 */
@SuppressWarnings("removal")
public final class UnsafeMemoryBackend implements MemoryBackend {

    private static final Unsafe UNSAFE = loadUnsafe();
    private static final long BYTE_ARRAY_BASE = Unsafe.ARRAY_BYTE_BASE_OFFSET;
    private static final int INITIAL_CAPACITY = 64;
    /** malloc already guarantees this much alignment on every supported platform. */
    private static final long NATURAL_ALIGNMENT = 16;

    /**
     * Published as a whole on growth, so one volatile read yields a consistent
     * view; element updates happen under {@link #tableLock}.
     */
    private record Table(long[] addresses, long[] sizes, long[] rawAddresses, int[] generations) {
    }

    private volatile Table table = new Table(
            new long[INITIAL_CAPACITY], new long[INITIAL_CAPACITY],
            new long[INITIAL_CAPACITY], new int[INITIAL_CAPACITY]);
    private int[] freeList = new int[INITIAL_CAPACITY];
    private int freeCount;
    private int nextIndex;
    private final Object tableLock = new Object();
    private boolean closed;

    private static Unsafe loadUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public long allocate(long byteSize, long alignment) {
        if (byteSize < 0 || alignment <= 0 || Long.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("Invalid allocation: size=" + byteSize + ", alignment=" + alignment);
        }
        // Over-allocate only when malloc's natural alignment is not enough.
        long padding = alignment > NATURAL_ALIGNMENT ? alignment - 1 : 0;
        long raw = UNSAFE.allocateMemory(Math.max(1, byteSize + padding)); // throws OutOfMemoryError
        long aligned = (raw + padding) & -alignment;
        UNSAFE.setMemory(aligned, byteSize, (byte) 0); // match FFM: allocations start zeroed

        synchronized (tableLock) {
            if (closed) {
                UNSAFE.freeMemory(raw);
                throw new IllegalStateException("UnsafeMemoryBackend is closed");
            }
            int index = freeCount > 0 ? freeList[--freeCount] : nextIndex++;
            Table t = table;
            if (index >= t.addresses().length) {
                int newLen = t.addresses().length << 1;
                t = new Table(Arrays.copyOf(t.addresses(), newLen), Arrays.copyOf(t.sizes(), newLen),
                        Arrays.copyOf(t.rawAddresses(), newLen), Arrays.copyOf(t.generations(), newLen));
            }
            int generation = nextGeneration(t.generations()[index]);
            t.generations()[index] = generation;
            t.sizes()[index] = byteSize;
            t.rawAddresses()[index] = raw;
            t.addresses()[index] = aligned;
            // Volatile write publishes every element store above to lock-free readers.
            table = t;
            return ((long) generation << 32) | index;
        }
    }

    @Override
    public void free(long handle) {
        long raw;
        synchronized (tableLock) {
            Table t = table;
            int index = (int) handle;
            if (index < 0 || index >= t.addresses().length
                    || t.generations()[index] != (int) (handle >>> 32) || t.addresses()[index] == 0L) {
                return; // stale, already freed, or never allocated -- idempotent
            }
            raw = t.rawAddresses()[index];
            t.addresses()[index] = 0L;
            t.sizes()[index] = 0L;
            t.rawAddresses()[index] = 0L;
            if (freeCount == freeList.length) {
                freeList = Arrays.copyOf(freeList, freeList.length << 1);
            }
            freeList[freeCount++] = index;
            table = t; // publish the cleared slot
        }
        UNSAFE.freeMemory(raw);
    }

    /**
     * Resolves {@code handle + offset} to an absolute address after validating
     * the handle and that {@code [offset, offset + length)} lies inside it.
     */
    private long address(long handle, long offset, long length) {
        Table t = table;
        int index = (int) handle;
        if (index >= 0 && index < t.addresses().length && t.generations()[index] == (int) (handle >>> 32)) {
            long base = t.addresses()[index];
            if (base != 0L) {
                // (offset | length) < 0 catches negatives; the subtraction form avoids overflow.
                if ((offset | length) < 0 || offset > t.sizes()[index] - length) {
                    throw new IndexOutOfBoundsException("Celeris: access [" + offset + ", " + (offset + length)
                            + ") outside allocation of " + t.sizes()[index] + " bytes");
                }
                return base + offset;
            }
        }
        throw new IllegalStateException("Celeris: access through freed/stale/unknown handle 0x"
                + Long.toHexString(handle));
    }

    private static long requireAligned(long address, int size) {
        // Volatile/ordered accesses must be naturally aligned to be atomic.
        if ((address & (size - 1)) != 0) {
            throw new IllegalArgumentException("Misaligned atomic access at 0x" + Long.toHexString(address));
        }
        return address;
    }

    private static int nextGeneration(int current) {
        return current == Integer.MAX_VALUE ? 1 : current + 1;
    }

    @Override
    public byte getByte(long handle, long offset) {
        return UNSAFE.getByte(address(handle, offset, 1));
    }

    @Override
    public void setByte(long handle, long offset, byte value) {
        UNSAFE.putByte(address(handle, offset, 1), value);
    }

    @Override
    public int getInt(long handle, long offset) {
        return UNSAFE.getInt(address(handle, offset, Integer.BYTES));
    }

    @Override
    public void setInt(long handle, long offset, int value) {
        UNSAFE.putInt(address(handle, offset, Integer.BYTES), value);
    }

    @Override
    public long getLong(long handle, long offset) {
        return UNSAFE.getLong(address(handle, offset, Long.BYTES));
    }

    @Override
    public void setLong(long handle, long offset, long value) {
        UNSAFE.putLong(address(handle, offset, Long.BYTES), value);
    }

    @Override
    public long getLongAcquire(long handle, long offset) {
        // Volatile load is at least as strong as acquire.
        return UNSAFE.getLongVolatile(null, requireAligned(address(handle, offset, Long.BYTES), Long.BYTES));
    }

    @Override
    public void setLongRelease(long handle, long offset, long value) {
        // putOrderedLong is a release store (StoreStore + LoadStore before it).
        UNSAFE.putOrderedLong(null, requireAligned(address(handle, offset, Long.BYTES), Long.BYTES), value);
    }

    @Override
    public int getIntAcquire(long handle, long offset) {
        return UNSAFE.getIntVolatile(null, requireAligned(address(handle, offset, Integer.BYTES), Integer.BYTES));
    }

    @Override
    public void setIntRelease(long handle, long offset, int value) {
        UNSAFE.putOrderedInt(null, requireAligned(address(handle, offset, Integer.BYTES), Integer.BYTES), value);
    }

    @Override
    public short getShort(long handle, long offset) {
        return UNSAFE.getShort(address(handle, offset, Short.BYTES));
    }

    @Override
    public void setShort(long handle, long offset, short value) {
        UNSAFE.putShort(address(handle, offset, Short.BYTES), value);
    }

    @Override
    public float getFloat(long handle, long offset) {
        return UNSAFE.getFloat(address(handle, offset, Float.BYTES));
    }

    @Override
    public void setFloat(long handle, long offset, float value) {
        UNSAFE.putFloat(address(handle, offset, Float.BYTES), value);
    }

    @Override
    public void fill(long handle, long offset, long length, byte value) {
        UNSAFE.setMemory(address(handle, offset, length), length, value);
    }

    @Override
    public void copyFromHeap(long handle, long dstOffset, byte[] src, int srcOffset, int length) {
        java.util.Objects.checkFromIndexSize(srcOffset, length, src.length);
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE + srcOffset, null, address(handle, dstOffset, length), length);
    }

    @Override
    public void copyToHeap(long handle, long srcOffset, byte[] dst, int dstOffset, int length) {
        java.util.Objects.checkFromIndexSize(dstOffset, length, dst.length);
        UNSAFE.copyMemory(null, address(handle, srcOffset, length), dst, BYTE_ARRAY_BASE + dstOffset, length);
    }

    @Override
    public void copy(long srcHandle, long srcOffset, long dstHandle, long dstOffset, long length) {
        // copyMemory has memmove semantics for off-heap ranges, so overlap is safe.
        UNSAFE.copyMemory(address(srcHandle, srcOffset, length), address(dstHandle, dstOffset, length), length);
    }

    @Override
    public boolean supportsRawAddress() {
        return true;
    }

    @Override
    public long rawAddress(long handle, long offset, long length) {
        return address(handle, offset, length);
    }

    @Override
    public String name() {
        return "Unsafe";
    }

    @Override
    public void close() {
        long[] toFree;
        synchronized (tableLock) {
            if (closed) {
                return;
            }
            closed = true;
            Table t = table;
            toFree = t.rawAddresses().clone();
            table = new Table(new long[0], new long[0], new long[0], new int[0]);
            freeCount = 0;
            nextIndex = 0;
        }
        for (long raw : toFree) {
            if (raw != 0L) {
                UNSAFE.freeMemory(raw);
            }
        }
    }
}
