package com.panzer.mods.celeris.network;

import com.panzer.mods.celeris.core.memory.CelerisCodecs;
import com.panzer.mods.celeris.core.memory.PacketPipeline;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Byte-array-in, byte-array-out compression for outbound/inbound network
 * payloads. Public API is unchanged by the FFM/compat-mode split -- callers
 * never see a {@code MemoryBackend} or a handle, only {@code byte[]} --
 * only the internals route through {@link PacketPipeline} on whichever
 * backend {@link CelerisRuntime} selected.
 */
public final class PayloadCompression {

    private static final int DEFAULT_THRESHOLD_BYTES = 256;
    private static final int DEFAULT_COMPRESSION_LEVEL = 3;

    // One pipeline (and its scratch allocations) per thread, so buffers are
    // reused without concurrency issues. Cleaner frees each pipeline's
    // scratch memory once its owning thread becomes unreachable, so
    // pooled/recycled server threads don't leak off-heap memory on either
    // backend.
    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    /**
     * Upper bound on a peer-declared decompressed size. originalSize arrives
     * from the network, so without a cap a malicious peer could make us
     * allocate ~2 GB per packet.
     */
    public static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

    private static final ThreadLocal<PacketPipeline> PIPELINE = ThreadLocal.withInitial(() -> {
        // Wire codec, not the local best codec: both peers must agree on the format.
        PacketPipeline pipeline = new PacketPipeline(DEFAULT_THRESHOLD_BYTES, DEFAULT_COMPRESSION_LEVEL,
                CelerisRuntime.backend(), CelerisCodecs.wireCodec());
        CLEANER.register(Thread.currentThread(), pipeline::close);
        return pipeline;
    });

    private PayloadCompression() {}

    /** Compresses {@code rawData} using the default threshold and compression level. */
    public static CompressedPayload compress(byte[] rawData) {
        return compress(rawData, DEFAULT_THRESHOLD_BYTES, DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Compresses {@code rawData}.
     *
     * <p><b>{@code thresholdBytes} and {@code compressionLevel} are not
     * currently applied.</b> Each thread's {@link PacketPipeline} is built
     * once, lazily, using the class defaults ({@value #DEFAULT_THRESHOLD_BYTES}
     * bytes / level {@value #DEFAULT_COMPRESSION_LEVEL}) -- these two
     * parameters are accepted but silently ignored on every call, including
     * the first. Until that's wired through, prefer {@link #compress(byte[])}
     * so the call site doesn't imply a control it doesn't have.
     */
    @SuppressWarnings("resource")
    public static CompressedPayload compress(byte[] rawData, int thresholdBytes, int compressionLevel) {
        PacketPipeline pipeline = PIPELINE.get();
        MemoryBackend backend = CelerisRuntime.backend();

        long srcHandle = backend.allocate(rawData.length, 8L);
        try {
            backend.copyFromHeap(srcHandle, 0L, rawData, 0, rawData.length);

            PacketPipeline.EncodedFrame frame = pipeline.encodeOutbound(srcHandle, 0L, rawData.length);

            byte[] wireBytes = new byte[(int) frame.size()];
            backend.copyToHeap(frame.handle(), frame.offset(), wireBytes, 0, wireBytes.length);

            return new CompressedPayload(wireBytes, frame.compressed(), rawData.length);
        } finally {
            backend.free(srcHandle);
        }
    }

    /** Reverses {@link #compress}, returning the original bytes. */
    @SuppressWarnings("resource")
    public static byte[] decompress(CompressedPayload payload) {
        int declared = payload.originalSize();
        if (declared < 0 || declared > MAX_DECOMPRESSED_SIZE) {
            throw new IllegalArgumentException("Celeris payload declares " + declared
                    + " decompressed bytes (max " + MAX_DECOMPRESSED_SIZE + ")");
        }
        PacketPipeline pipeline = PIPELINE.get();
        MemoryBackend backend = CelerisRuntime.backend();
        byte[] wireBytes = payload.data();

        long srcHandle = backend.allocate(wireBytes.length, 8L);
        try {
            backend.copyFromHeap(srcHandle, 0L, wireBytes, 0, wireBytes.length);

            PacketPipeline.DecodedFrame decoded = pipeline.decodeInbound(
                    srcHandle, 0L, wireBytes.length, payload.compressed(), payload.originalSize());

            byte[] result = new byte[(int) decoded.size()];
            backend.copyToHeap(decoded.handle(), decoded.offset(), result, 0, result.length);
            return result;
        } finally {
            backend.free(srcHandle);
        }
    }
}
