package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Native zstd over any backend that exposes raw addresses (FFM and Unsafe;
 * see {@link MemoryBackend#supportsRawAddress()}), through either binding:
 * FFM where the JVM has it, JNI otherwise (Java 21 without flags).
 *
 * <p>Compression and decompression contexts come from two
 * {@link NativeContextPool}s instead of being created per call (what
 * {@code ZSTD_compress}/{@code ZSTD_decompress} do internally), so a call
 * costs only the compression work itself. Addresses are resolved and
 * bounds-checked by the backend before any native code runs, and
 * {@link #compressBound} and error decoding are plain Java, so each
 * operation is exactly one native call.
 *
 * <p>Thread-safe: any number of threads may share one instance.
 */
public final class ZstdCompressionCodec implements CompressionCodec {

    /** {@code ZSTD_error_maxCode}: results in [-maxCode + 1, -1] are errors (what {@code ZSTD_isError} tests). */
    private static final long ZSTD_ERROR_MAX_CODE = 120;

    private final ZstdBinding binding;
    private final NativeContextPool compressContexts;
    private final NativeContextPool decompressContexts;

    ZstdCompressionCodec(ZstdBinding binding) {
        this(binding, NativeContextPool.defaultCapacity());
    }

    ZstdCompressionCodec(ZstdBinding binding, int poolCapacity) {
        this.binding = binding;
        this.compressContexts = new NativeContextPool("ZSTD_CCtx", poolCapacity, binding::createCCtx, binding::freeCCtx);
        this.decompressContexts = new NativeContextPool("ZSTD_DCtx", poolCapacity, binding::createDCtx, binding::freeDCtx);
    }

    @Override
    public long compressBound(long srcSize) {
        if (srcSize < 0) {
            throw new IllegalArgumentException("Negative size: " + srcSize);
        }
        // ZSTD_COMPRESSBOUND from zstd.h.
        long margin = srcSize < (128L << 10) ? ((128L << 10) - srcSize) >>> 11 : 0L;
        return srcSize + (srcSize >>> 8) + margin;
    }

    @Override
    public long compress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize, int level) {
        long dst = address(backend, dstHandle, 0L, dstCapacity);
        long src = address(backend, srcHandle, srcOffset, srcSize);
        long cctx = compressContexts.acquire();
        long result;
        try {
            result = binding.compress(cctx, dst, dstCapacity, src, srcSize, level);
        } finally {
            // ZSTD_compressCCtx resets the context on entry, so it is reusable even after an error.
            compressContexts.release(cctx);
        }
        return check(result, "compression");
    }

    @Override
    public long decompress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize) {
        long dst = address(backend, dstHandle, 0L, dstCapacity);
        long src = address(backend, srcHandle, srcOffset, srcSize);
        long dctx = decompressContexts.acquire();
        long result;
        try {
            result = binding.decompress(dctx, dst, dstCapacity, src, srcSize);
        } finally {
            decompressContexts.release(dctx);
        }
        return check(result, "decompression");
    }

    @Override
    public String name() {
        return "zstd";
    }

    /** How native code is reached: "JNI" or "FFM". */
    public String bindingName() {
        return binding.name();
    }

    /** Version of the loaded libzstd, e.g. {@code "1.5.7"}. */
    public String nativeVersion() {
        int v = binding.versionNumber();
        return (v / 10000) + "." + (v / 100 % 100) + "." + (v % 100);
    }

    /** Frees the idle pooled contexts (the codec stays usable and recreates them on demand). */
    public void releaseIdleContexts() {
        compressContexts.clear();
        decompressContexts.clear();
    }

    private static long address(MemoryBackend backend, long handle, long offset, long length) {
        long address = backend.rawAddress(handle, offset, length);
        if (address == 0L) {
            throw new IllegalStateException("Celeris zstd needs raw addresses, which the "
                    + backend.name() + " memory backend does not provide");
        }
        return address;
    }

    private static long check(long result, String operation) {
        if (Long.compareUnsigned(result, -ZSTD_ERROR_MAX_CODE) > 0) {
            int code = (int) -result;
            throw new IllegalStateException("Celeris zstd " + operation + " failed: " + errorName(code)
                    + " (zstd error " + code + ")");
        }
        return result;
    }

    /** Names from zstd_errors.h (1.5.x) for the codes these calls can return. */
    static String errorName(int code) {
        return switch (code) {
            case 1 -> "generic error";
            case 10 -> "unknown frame descriptor";
            case 12 -> "version not supported";
            case 14 -> "unsupported frame parameter";
            case 16 -> "frame requires too much memory for decoding";
            case 20 -> "data corruption detected";
            case 22 -> "checksum mismatch";
            case 24 -> "header of literals block is wrong";
            case 30, 32 -> "dictionary corrupted or wrong";
            case 40, 41, 42 -> "unsupported or out-of-bound parameter";
            case 64 -> "allocation error: not enough memory";
            case 66 -> "workspace too small";
            case 70 -> "destination buffer is too small";
            case 72 -> "source size is wrong";
            case 74 -> "destination buffer is null";
            default -> "error";
        };
    }
}
