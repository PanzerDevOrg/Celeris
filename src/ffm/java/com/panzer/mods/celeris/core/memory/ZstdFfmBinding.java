package com.panzer.mods.celeris.core.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * libzstd through FFM downcalls, used when the FFM backend is active (Java 25,
 * or 21 with {@code --enable-preview}). Pointers are declared as
 * {@code JAVA_LONG} rather than {@code ADDRESS}: on every 64-bit ABI Celeris
 * ships (System V x86_64, Windows x64, AArch64) a pointer and a 64-bit integer
 * travel in the same register, and plain longs mean no {@link MemorySegment}
 * is created per call. Loading the class loads the library and binds every
 * symbol, and throws if either is missing; {@code CelerisCodecs} only loads
 * it reflectively, inside a try.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class ZstdFfmBinding implements ZstdBinding {

    private static final MethodHandle VERSION_NUMBER;
    private static final MethodHandle CREATE_CCTX;
    private static final MethodHandle FREE_CCTX;
    private static final MethodHandle CREATE_DCTX;
    private static final MethodHandle FREE_DCTX;
    private static final MethodHandle COMPRESS_CCTX;
    private static final MethodHandle DECOMPRESS_DCTX;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup zstd = SymbolLookup.libraryLookup(ZstdLibrary.path(), Arena.global());
        VERSION_NUMBER = linker.downcallHandle(find(zstd, "ZSTD_versionNumber"), FunctionDescriptor.of(JAVA_INT));
        CREATE_CCTX = linker.downcallHandle(find(zstd, "ZSTD_createCCtx"), FunctionDescriptor.of(JAVA_LONG));
        FREE_CCTX = linker.downcallHandle(find(zstd, "ZSTD_freeCCtx"), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        CREATE_DCTX = linker.downcallHandle(find(zstd, "ZSTD_createDCtx"), FunctionDescriptor.of(JAVA_LONG));
        FREE_DCTX = linker.downcallHandle(find(zstd, "ZSTD_freeDCtx"), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        COMPRESS_CCTX = linker.downcallHandle(find(zstd, "ZSTD_compressCCtx"), FunctionDescriptor.of(JAVA_LONG,
                JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
        DECOMPRESS_DCTX = linker.downcallHandle(find(zstd, "ZSTD_decompressDCtx"), FunctionDescriptor.of(JAVA_LONG,
                JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    }

    public ZstdFfmBinding() {
    }

    private static MemorySegment find(SymbolLookup lookup, String symbol) {
        return lookup.find(symbol).orElseThrow(() -> new IllegalStateException("libzstd lacks " + symbol));
    }

    @Override
    public String name() {
        return "FFM";
    }

    @Override
    public int versionNumber() {
        try {
            return (int) VERSION_NUMBER.invokeExact();
        } catch (Throwable t) {
            throw failure("ZSTD_versionNumber", t);
        }
    }

    @Override
    public long createCCtx() {
        try {
            return (long) CREATE_CCTX.invokeExact();
        } catch (Throwable t) {
            throw failure("ZSTD_createCCtx", t);
        }
    }

    @Override
    public void freeCCtx(long cctx) {
        try {
            long ignored = (long) FREE_CCTX.invokeExact(cctx);
        } catch (Throwable t) {
            throw failure("ZSTD_freeCCtx", t);
        }
    }

    @Override
    public long createDCtx() {
        try {
            return (long) CREATE_DCTX.invokeExact();
        } catch (Throwable t) {
            throw failure("ZSTD_createDCtx", t);
        }
    }

    @Override
    public void freeDCtx(long dctx) {
        try {
            long ignored = (long) FREE_DCTX.invokeExact(dctx);
        } catch (Throwable t) {
            throw failure("ZSTD_freeDCtx", t);
        }
    }

    @Override
    public long compress(long cctx, long dst, long dstCapacity, long src, long srcSize, int level) {
        try {
            return (long) COMPRESS_CCTX.invokeExact(cctx, dst, dstCapacity, src, srcSize, level);
        } catch (Throwable t) {
            throw failure("ZSTD_compressCCtx", t);
        }
    }

    @Override
    public long decompress(long dctx, long dst, long dstCapacity, long src, long srcSize) {
        try {
            return (long) DECOMPRESS_DCTX.invokeExact(dctx, dst, dstCapacity, src, srcSize);
        } catch (Throwable t) {
            throw failure("ZSTD_decompressDCtx", t);
        }
    }

    private static IllegalStateException failure(String call, Throwable t) {
        return new IllegalStateException(call + " downcall failed", t);
    }
}
