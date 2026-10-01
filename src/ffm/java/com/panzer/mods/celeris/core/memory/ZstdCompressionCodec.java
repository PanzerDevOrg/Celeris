package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

import java.lang.foreign.MemorySegment;

/**
 * Adapts the raw {@link ZstdCodec} FFI bindings to {@link CompressionCodec}.
 * Requires {@code backend} to be an {@link FfmMemoryBackend}, since native
 * zstd needs a real pointer to operate on -- {@link PacketPipeline} only
 * ever constructs this class when {@code CelerisRuntime.backend()} already
 * returned an {@code FfmMemoryBackend}, so this requirement is never
 * actually violated in practice, but it's enforced explicitly here rather
 * than trusted silently, since a future refactor could otherwise wire this
 * codec to the wrong backend without any compile-time signal.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class ZstdCompressionCodec implements CompressionCodec {

    @Override
    public long compressBound(long srcSize) {
        return ZstdCodec.compressBound(srcSize);
    }

    @Override
    public long compress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize, int level) {
        FfmMemoryBackend ffm = requireFfm(backend);
        MemorySegment dst = ffm.segmentOf(dstHandle);
        MemorySegment src = ffm.segmentOf(srcHandle).asSlice(srcOffset, srcSize);
        return ZstdCodec.compress(dst, dstCapacity, src, srcSize, level);
    }

    @Override
    public long decompress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize) {
        FfmMemoryBackend ffm = requireFfm(backend);
        MemorySegment dst = ffm.segmentOf(dstHandle);
        MemorySegment src = ffm.segmentOf(srcHandle).asSlice(srcOffset, srcSize);
        return ZstdCodec.decompress(dst, dstCapacity, src, srcSize);
    }

    private static FfmMemoryBackend requireFfm(MemoryBackend backend) {
        if (backend instanceof FfmMemoryBackend ffm) {
            return ffm;
        }
        throw new IllegalStateException(
                "ZstdCompressionCodec requires the FFM backend, got: " + backend.name() + " -- this indicates a wiring bug, not a runtime environment issue (CelerisRuntime should never hand out a non-FFM backend alongside this codec)");
    }

    @Override
    public String name() {
        return "zstd";
    }
}
