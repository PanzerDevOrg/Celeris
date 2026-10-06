package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Compression codec abstraction, so {@link PacketPipeline} never calls
 * {@code ZstdCodec} directly. This matters beyond just swapping algorithms:
 * {@code ZstdCodec}'s static initializer performs FFM downcall setup the
 * moment the class is loaded, which throws on a JVM without usable FFM --
 * simply having a code path that references {@code ZstdCodec.class} would
 * be enough to blow up compat mode even if that path is never executed.
 * Routing everything through this interface means the FFM-backed
 * implementation class is only ever loaded when {@code CelerisRuntime} has
 * already confirmed FFM works.
 *
 * <p>All methods operate through {@link MemoryBackend} handles, matching
 * the rest of the memory subsystem -- no {@code MemorySegment} in this
 * contract.
 */
public interface CompressionCodec {

    /** Upper bound on compressed size for a given input size, used to size the destination buffer. */
    long compressBound(long srcSize);

    /**
     * Compresses {@code srcSize} bytes from {@code (srcHandle, srcOffset)}
     * into {@code (dstHandle, 0)}, both on {@code backend}.
     *
     * @return the number of compressed bytes written
     */
    long compress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize, int level);

    /**
     * Decompresses {@code srcSize} bytes from {@code (srcHandle, srcOffset)}
     * into {@code (dstHandle, 0)}, both on {@code backend}.
     *
     * @return the number of decompressed bytes written
     */
    long decompress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize);

    /** Short name for logging (e.g. "zstd", "deflate"). */
    String name();
}
