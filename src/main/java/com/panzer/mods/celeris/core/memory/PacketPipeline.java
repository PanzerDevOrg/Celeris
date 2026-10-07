package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Compresses/decompresses packet payloads through whichever {@link
 * CompressionCodec} {@link CelerisCodecs} selected (native zstd on the FFM
 * and Unsafe backends, deflate in compat mode) -- this class itself is codec-agnostic
 * and backend-agnostic, operating purely through {@link MemoryBackend}
 * handles.
 */
public final class PacketPipeline implements AutoCloseable {

    private static final long SCRATCH_ALIGNMENT_BYTES = 64L;
    private static final int SCRATCH_GROWTH_SHIFT = 1;

    private final int compressionThresholdBytes;
    private final int compressionLevel;
    private final MemoryBackend backend;
    private final CompressionCodec codec;

    @SuppressWarnings("FieldMayBeFinal")
    private long encodeScratchHandle = -1L;
    private long encodeScratchCapacity;
    private long decodeScratchHandle = -1L;
    private long decodeScratchCapacity;

    public PacketPipeline(int compressionThresholdBytes, int compressionLevel) {
        this(compressionThresholdBytes, compressionLevel, CelerisRuntime.backend(), CelerisCodecs.compressionCodec());
    }

    public PacketPipeline(int compressionThresholdBytes, int compressionLevel, MemoryBackend backend, CompressionCodec codec) {
        this.compressionThresholdBytes = compressionThresholdBytes;
        this.compressionLevel = compressionLevel;
        this.backend = backend;
        this.codec = codec;
    }

    // Separate encode/decode scratch: a shared buffer let decodeInbound overwrite
    // an EncodedFrame the caller was still holding, and encodeOutbound passed the
    // never-updated encodeScratchCapacity (always 0) as the destination size.
    private long ensureEncodeScratch(long requiredBytes) {
        if (encodeScratchHandle < 0 || encodeScratchCapacity < requiredBytes) {
            if (encodeScratchHandle >= 0) {
                backend.free(encodeScratchHandle);
            }
            long grown = Math.max(requiredBytes, encodeScratchCapacity << SCRATCH_GROWTH_SHIFT);
            encodeScratchHandle = backend.allocate(grown, SCRATCH_ALIGNMENT_BYTES);
            encodeScratchCapacity = grown;
        }
        return encodeScratchHandle;
    }

    private long ensureDecodeScratch(long requiredBytes) {
        if (decodeScratchHandle < 0 || decodeScratchCapacity < requiredBytes) {
            if (decodeScratchHandle >= 0) {
                backend.free(decodeScratchHandle);
            }
            long grown = Math.max(requiredBytes, decodeScratchCapacity << SCRATCH_GROWTH_SHIFT);
            decodeScratchHandle = backend.allocate(grown, SCRATCH_ALIGNMENT_BYTES);
            decodeScratchCapacity = grown;
        }
        return decodeScratchHandle;
    }

    /** Compresses only if it actually helps; below the threshold, or if compression didn't shrink the payload, returns the input unchanged with {@code compressed=false}. */
    public EncodedFrame encodeOutbound(long payloadHandle, long payloadOffset, long payloadSize) {
        if (payloadSize < compressionThresholdBytes) {
            return new EncodedFrame(payloadHandle, payloadOffset, payloadSize, false);
        }

        long bound = codec.compressBound(payloadSize);
        long dstHandle = ensureEncodeScratch(bound);
        long written = codec.compress(backend, dstHandle, encodeScratchCapacity, payloadHandle, payloadOffset, payloadSize, compressionLevel);

        if (written >= payloadSize) {
            return new EncodedFrame(payloadHandle, payloadOffset, payloadSize, false);
        }
        return new EncodedFrame(dstHandle, 0L, written, true);
    }

    /** Reverses {@link #encodeOutbound}; {@code compressed} and {@code knownDecompressedSize} must be whatever the encoding side reported. */
    public DecodedFrame decodeInbound(long frameHandle, long frameOffset, long frameSize, boolean compressed, long knownDecompressedSize) {
        if (!compressed) {
            return new DecodedFrame(frameHandle, frameOffset, frameSize);
        }

        long dstHandle = ensureDecodeScratch(knownDecompressedSize);
        long written = codec.decompress(backend, dstHandle, decodeScratchCapacity, frameHandle, frameOffset, frameSize);
        return new DecodedFrame(dstHandle, 0L, written);
    }

    @Override
    public void close() {
        if (encodeScratchHandle >= 0) {
            backend.free(encodeScratchHandle);
        }
        if (decodeScratchHandle >= 0) {
            backend.free(decodeScratchHandle);
        }
    }

    /**
     * Result of {@link #encodeOutbound}. {@code handle}/{@code offset} point at scratch
     * memory owned by this pipeline when {@code compressed} is true -- valid only until
     * the next {@code encodeOutbound} call on the same instance, so copy out (as
     * {@code PayloadCompression} does) before calling again.
     */
    public record EncodedFrame(long handle, long offset, long size, boolean compressed) {
    }

    /**
     * Result of {@link #decodeInbound}. Same scratch-memory lifetime caveat as {@link
     * EncodedFrame} applies here for the decompressed case.
     */
    public record DecodedFrame(long handle, long offset, long size) {
    }
}
