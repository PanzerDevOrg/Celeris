package com.panzer.mods.celeris.core.memory;

/**
 * Test-only accessor exposing the package-private {@link ZstdCompressionCodec}
 * to tests living outside this package.
 */
public final class ZstdCompressionCodecTestAccess {
    private ZstdCompressionCodecTestAccess() {}

    public static CompressionCodec create() {
        return new ZstdCompressionCodec();
    }
}
