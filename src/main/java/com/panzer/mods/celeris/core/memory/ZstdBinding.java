package com.panzer.mods.celeris.core.memory;

/**
 * The handful of libzstd calls {@link ZstdCompressionCodec} needs, with every
 * pointer passed as a raw address ({@code long}): no {@code MemorySegment}
 * and no allocation per call, so the same contract works over JNI (Java 21
 * without flags, {@link ZstdJniBinding}) and over FFM ({@code ZstdFfmBinding}
 * in the {@code ffm} source set).
 *
 * <p>Return values are libzstd's own: sizes, or {@code (size_t) -errorCode},
 * which reads as a small negative {@code long}; the codec checks them.
 */
interface ZstdBinding {

    /** "JNI" or "FFM", for logs. */
    String name();

    /** {@code ZSTD_versionNumber()}: major * 10000 + minor * 100 + patch. */
    int versionNumber();

    long createCCtx();

    void freeCCtx(long cctx);

    long createDCtx();

    void freeDCtx(long dctx);

    /** {@code ZSTD_compressCCtx}. */
    long compress(long cctx, long dst, long dstCapacity, long src, long srcSize, int level);

    /** {@code ZSTD_decompressDCtx}. */
    long decompress(long dctx, long dst, long dstCapacity, long src, long srcSize);
}
