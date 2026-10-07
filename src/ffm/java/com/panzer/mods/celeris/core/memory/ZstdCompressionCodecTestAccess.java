package com.panzer.mods.celeris.core.memory;

/**
 * Test-only accessor building {@link ZstdCompressionCodec} on the FFM binding,
 * for tests living outside this package. The JNI counterpart is
 * {@link ZstdJniTestAccess}.
 */
public final class ZstdCompressionCodecTestAccess {
    private ZstdCompressionCodecTestAccess() {}

    public static CompressionCodec create() {
        return new ZstdCompressionCodec(new ZstdFfmBinding());
    }
}
