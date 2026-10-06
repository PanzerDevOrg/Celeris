package com.panzer.mods.celeris.core.memory;

/**
 * Test-only accessor exposing the package-private {@link DeflateCompressionCodec}
 * to tests living outside this package.
 */
public final class DeflateCompressionCodecTestAccess {
    private DeflateCompressionCodecTestAccess() {}

    public static CompressionCodec create() {
        return new DeflateCompressionCodec();
    }
}
