package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Compression codec abstraction, so {@link PacketPipeline} never names a
 * native binding directly. This matters beyond just swapping algorithms: the
 * zstd bindings load libzstd in their static initializers, which throws on a
 * platform without it (and the FFM one also needs a JVM with usable FFM).
 * Routing everything through this interface means those classes are only
 * ever loaded by {@code CelerisCodecs}, inside a try, with deflate as the
 * fallback.
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
