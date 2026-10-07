package com.panzer.mods.celeris.core.memory.backend;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Compatibility path: backs every handle with a {@link ByteBuffer#allocateDirect}
 * buffer instead of a {@code MemorySegment}. Still off-heap (the JVM allocates
 * these outside the managed heap exactly like FFM segments) and still
 * zero-copy for bulk transfers via {@link ByteBuffer#put}/{@link ByteBuffer#get}
 * -- what's lost versus {@link FfmMemoryBackend} is the ability to hand a raw
 * pointer to native code (so native zstd is unavailable in this mode and
 * {@code CelerisCodecs} picks deflate instead; {@link #byteBuffer} still lets
 * deflate work on these buffers in place).
 *
 * <p>Byte order is pinned to {@link ByteOrder#LITTLE_ENDIAN} to match the
 * layout {@link FfmMemoryBackend} produces via {@code JAVA_INT_UNALIGNED}/
 * {@code JAVA_LONG_UNALIGNED} on every platform this mod targets (x86_64,
 * aarch64) -- so a wire format written by one backend reads correctly under
 * the other, which matters for {@code FrameHeader} and any persisted bytes.
 */
@SuppressWarnings("JavadocReference")
public final class HeapMemoryBackend implements MemoryBackend {

    private static final int INITIAL_CAPACITY = 64;
    // Real acquire/release semantics over a direct ByteBuffer -- unlike
    // plain getLong/putLong, this VarHandle gives the same happens-before
    // guarantee FfmMemoryBackend's aligned-layout VarHandle gives, which
    // MpscRingBuffer's cross-thread state-word protocol depends on for
    // correctness, not just performance.
    private static final VarHandle LONG_ACQUIRE_RELEASE_HANDLE =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_ACQUIRE_RELEASE_HANDLE =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle BUFFER_ELEMENT =
            MethodHandles.arrayElementVarHandle(ByteBuffer[].class);

    // Same lock-free handle table design as FfmMemoryBackend: readers do one
    // volatile array read + one acquire element read; allocate/free serialize
    // on `tableLock` and publish grown tables whole. Freed handles are
    // recycled via `freeList`.
    private volatile ByteBuffer[] buffers = new ByteBuffer[INITIAL_CAPACITY];
    private int[] freeList = new int[INITIAL_CAPACITY];
    private int freeCount;
    private int nextHandle;
    private final Object tableLock = new Object();
    private boolean closed;

    @Override
    public long allocate(long byteSize, long alignment) {
        if (byteSize < 0 || byteSize > Integer.MAX_VALUE - 64) {
            throw new IllegalArgumentException("HeapMemoryBackend cannot allocate " + byteSize + " bytes");
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a power of two: " + alignment);
        }
        // ByteBuffer has no alignment control, so over-allocate and slice to
        // the first aligned address. Direct buffers are typically already
        // 8/16-byte aligned, so the slice is usually a no-op skip of 0.
        ByteBuffer buffer = alignedDirect((int) byteSize, (int) Math.min(alignment, 4096));
        synchronized (tableLock) {
            if (closed) {
                throw new IllegalStateException("HeapMemoryBackend is closed");
            }
            int handle = freeCount > 0 ? freeList[--freeCount] : nextHandle++;
            ByteBuffer[] table = buffers;
            if (handle >= table.length) {
                ByteBuffer[] grown = new ByteBuffer[table.length << 1];
                System.arraycopy(table, 0, grown, 0, table.length);
                grown[handle] = buffer;
                buffers = grown;
            } else {
                BUFFER_ELEMENT.setRelease(table, handle, buffer);
            }
            return handle;
        }
    }

    private static ByteBuffer alignedDirect(int size, int alignment) {
        if (alignment <= 1) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        }
        // NOT alignedSlice(): that also rounds the *limit* down to the
        // alignment, silently shrinking the buffer below `size`. Compute the
        // aligned start ourselves and slice exactly `size` bytes from there.
        ByteBuffer raw = ByteBuffer.allocateDirect(size + alignment - 1);
        int misalignment = raw.alignmentOffset(0, alignment);
        int start = misalignment == 0 ? 0 : alignment - misalignment;
        return raw.slice(start, size).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void free(long handle) {
        int h = (int) handle;
        synchronized (tableLock) {
            ByteBuffer[] table = buffers;
            if (h < 0 || h >= table.length || table[h] == null) {
                return; // idempotent
            }
            BUFFER_ELEMENT.setRelease(table, h, null);
            if (freeCount == freeList.length) {
                freeList = Arrays.copyOf(freeList, freeList.length << 1);
            }
            freeList[freeCount++] = h;
        }
        // Direct memory is released by the buffer's Cleaner once unreachable;
        // dropping the table reference is the only step available to us here.
    }

    private ByteBuffer bufferOf(long handle) {
        ByteBuffer b = (ByteBuffer) BUFFER_ELEMENT.getAcquire(buffers, (int) handle);
        if (b == null) {
            throw new IllegalStateException("Celeris: access through freed/unknown handle " + handle);
        }
        return b;
    }

    @Override
    public byte getByte(long handle, long offset) {
        return bufferOf(handle).get((int) offset);
    }

    @Override
    public void setByte(long handle, long offset, byte value) {
        bufferOf(handle).put((int) offset, value);
    }

    @Override
    public int getInt(long handle, long offset) {
        return bufferOf(handle).getInt((int) offset);
    }

    @Override
    public void setInt(long handle, long offset, int value) {
        bufferOf(handle).putInt((int) offset, value);
    }

    @Override
    public long getLong(long handle, long offset) {
        return bufferOf(handle).getLong((int) offset);
    }

    @Override
    public void setLong(long handle, long offset, long value) {
        bufferOf(handle).putLong((int) offset, value);
    }

    @Override
    public long getLongAcquire(long handle, long offset) {
        return (long) LONG_ACQUIRE_RELEASE_HANDLE.getAcquire(bufferOf(handle), (int) offset);
    }

    @Override
    public void setLongRelease(long handle, long offset, long value) {
        LONG_ACQUIRE_RELEASE_HANDLE.setRelease(bufferOf(handle), (int) offset, value);
    }

    @Override
    public int getIntAcquire(long handle, long offset) {
        return (int) INT_ACQUIRE_RELEASE_HANDLE.getAcquire(bufferOf(handle), (int) offset);
    }

    @Override
    public void setIntRelease(long handle, long offset, int value) {
        INT_ACQUIRE_RELEASE_HANDLE.setRelease(bufferOf(handle), (int) offset, value);
    }

    @Override
    public short getShort(long handle, long offset) {
        return bufferOf(handle).getShort((int) offset);
    }

    @Override
    public void setShort(long handle, long offset, short value) {
        bufferOf(handle).putShort((int) offset, value);
    }

    @Override
    public float getFloat(long handle, long offset) {
        return bufferOf(handle).getFloat((int) offset);
    }

    @Override
    public void setFloat(long handle, long offset, float value) {
        bufferOf(handle).putFloat((int) offset, value);
    }

    @Override
    public void fill(long handle, long offset, long length, byte value) {
        ByteBuffer b = bufferOf(handle);
        int off = (int) offset;
        int end = (int) (offset + length);
        // 8 bytes per store on the bulk of the range, byte tail.
        long word = (value & 0xFFL) * 0x0101010101010101L;
        int i = off;
        for (int wordEnd = end - 7; i < wordEnd; i += Long.BYTES) {
            b.putLong(i, word);
        }
        for (; i < end; i++) {
            b.put(i, value);
        }
    }

    // Absolute bulk transfers (Java 13+/16+) -- no duplicate()/position()
    // views allocated per call, and safe for concurrent callers on the same
    // buffer since no shared cursor state is touched.

    @Override
    public void copyFromHeap(long handle, long dstOffset, byte[] src, int srcOffset, int length) {
        bufferOf(handle).put((int) dstOffset, src, srcOffset, length);
    }

    @Override
    public void copyToHeap(long handle, long srcOffset, byte[] dst, int dstOffset, int length) {
        bufferOf(handle).get((int) srcOffset, dst, dstOffset, length);
    }

    @Override
    public void copy(long srcHandle, long srcOffset, long dstHandle, long dstOffset, long length) {
        bufferOf(dstHandle).put((int) dstOffset, bufferOf(srcHandle), (int) srcOffset, (int) length);
    }

    @Override
    public ByteBuffer byteBuffer(long handle, long offset, int length) {
        return bufferOf(handle).slice(Math.toIntExact(offset), length);
    }

    @Override
    public String name() {
        return "Heap (compat mode)";
    }

    @Override
    public void close() {
        synchronized (tableLock) {
            closed = true;
            buffers = new ByteBuffer[0];
            freeCount = 0;
            nextHandle = 0;
        }
    }
}
