package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.util.math.BranchlessTables;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns a set of named memory "channels", each an allocation on whichever
 * {@link MemoryBackend} {@link CelerisRuntime} selected for this JVM (FFM,
 * Unsafe, or the pure-Java heap fallback). Callers address channels by an
 * opaque {@code int} id and never see the backend directly.
 */
public final class MemoryBus implements AutoCloseable {

    private static final long DEFAULT_CHANNEL_BYTES = 1L << 20;
    private static final long CHANNEL_ALIGNMENT_BYTES = 64L;

    private final MemoryBackend backend;
    private final ConcurrentHashMap<Integer, Long> channelHandles;
    private final AtomicInteger channelIdGenerator;

    public MemoryBus() {
        this(CelerisRuntime.backend());
    }

    public MemoryBus(MemoryBackend backend) {
        this.backend = backend;
        this.channelHandles = new ConcurrentHashMap<>();
        this.channelIdGenerator = new AtomicInteger(0);
    }

    public int openChannel(long byteSize) {
        long alignedSize = BranchlessTables.alignUp(byteSize, CHANNEL_ALIGNMENT_BYTES);
        long handle = backend.allocate(alignedSize, CHANNEL_ALIGNMENT_BYTES);
        int id = channelIdGenerator.getAndIncrement();
        channelHandles.put(id, handle);
        return id;
    }

    public int openChannel() {
        return openChannel(DEFAULT_CHANNEL_BYTES);
    }

    public MemoryBackend backend() {
        return backend;
    }

    long handleOf(int channelId) {
        Long handle = channelHandles.get(channelId);
        if (handle == null) {
            throw new IllegalStateException("Unknown Celeris memory channel: " + channelId);
        }
        return handle;
    }

    public void closeChannel(int channelId) {
        Long handle = channelHandles.remove(channelId);
        if (handle != null) {
            backend.free(handle);
        }
    }

    public long readLong(int channelId, long offset) {
        return backend.getLong(handleOf(channelId), offset);
    }

    public void writeLong(int channelId, long offset, long value) {
        backend.setLong(handleOf(channelId), offset, value);
    }

    public void copyFromHeap(int channelId, long dstOffset, byte[] src, int srcOffset, int length) {
        backend.copyFromHeap(handleOf(channelId), dstOffset, src, srcOffset, length);
    }

    public void copyToHeap(int channelId, long srcOffset, byte[] dst, int dstOffset, int length) {
        backend.copyToHeap(handleOf(channelId), srcOffset, dst, dstOffset, length);
    }

    @Override
    public void close() {
        for (Long handle : channelHandles.values()) {
            backend.free(handle);
        }
        channelHandles.clear();
        // Deliberately does NOT close the backend: it is JVM-shared
        // (CelerisRuntime.backend()) and owned by the runtime, not this bus.
        // Freed channel handles are reclaimed eagerly by backend.free().
    }
}
