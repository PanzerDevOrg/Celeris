package com.panzer.mods.celeris.core.memory;

/**
 * Test-only accessor building {@link ZstdCompressionCodec} on the JNI binding
 * (the Java 21 no-flags path), for tests living outside this package.
 */
public final class ZstdJniTestAccess {
    private ZstdJniTestAccess() {}

    /** Whether this platform's bundled libzstd loads over JNI; tests skip when it does not. */
    public static boolean isAvailable() {
        try {
            return ZstdJniBinding.INSTANCE.versionNumber() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static CompressionCodec create() {
        return new ZstdCompressionCodec(ZstdJniBinding.INSTANCE);
    }

    /** A codec whose pools hold at most {@code poolCapacity} idle contexts, to exercise overflow. */
    public static CompressionCodec create(int poolCapacity) {
        return new ZstdCompressionCodec(ZstdJniBinding.INSTANCE, poolCapacity);
    }
}
