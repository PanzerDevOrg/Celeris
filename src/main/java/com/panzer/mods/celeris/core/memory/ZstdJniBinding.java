package com.panzer.mods.celeris.core.memory;

/**
 * libzstd through the JNI entry points compiled into Celeris's own build of it
 * ({@code native/zstd/celeris_zstd_jni.c}). This is what gives Java 21 native
 * zstd without {@code --enable-preview}: JNI needs no launch flag there.
 *
 * <p>Loading the class loads the library, and fails with an
 * {@link UnsatisfiedLinkError} (or an {@link ExceptionInInitializerError}
 * around it) on a platform without one; {@code CelerisCodecs} catches that
 * and falls back to deflate. All arguments are primitives, so each call is a
 * plain JNI transition with nothing pinned or copied.
 */
final class ZstdJniBinding implements ZstdBinding {

    static {
        System.load(ZstdLibrary.path().toAbsolutePath().toString());
    }

    static final ZstdJniBinding INSTANCE = new ZstdJniBinding();

    private ZstdJniBinding() {
    }

    @Override
    public String name() {
        return "JNI";
    }

    @Override
    public int versionNumber() {
        return nativeVersionNumber();
    }

    @Override
    public long createCCtx() {
        return nativeCreateCCtx();
    }

    @Override
    public void freeCCtx(long cctx) {
        nativeFreeCCtx(cctx);
    }

    @Override
    public long createDCtx() {
        return nativeCreateDCtx();
    }

    @Override
    public void freeDCtx(long dctx) {
        nativeFreeDCtx(dctx);
    }

    @Override
    public long compress(long cctx, long dst, long dstCapacity, long src, long srcSize, int level) {
        return nativeCompressCCtx(cctx, dst, dstCapacity, src, srcSize, level);
    }

    @Override
    public long decompress(long dctx, long dst, long dstCapacity, long src, long srcSize) {
        return nativeDecompressDCtx(dctx, dst, dstCapacity, src, srcSize);
    }

    private static native int nativeVersionNumber();

    private static native long nativeCreateCCtx();

    private static native void nativeFreeCCtx(long cctx);

    private static native long nativeCreateDCtx();

    private static native void nativeFreeDCtx(long dctx);

    private static native long nativeCompressCCtx(long cctx, long dst, long dstCapacity, long src, long srcSize, int level);

    private static native long nativeDecompressDCtx(long dctx, long dst, long dstCapacity, long src, long srcSize);
}
