package com.panzer.mods.celeris.network;

import com.panzer.mods.celeris.core.memory.HeapDeflate;

/**
 * Byte-array-in, byte-array-out compression for outbound/inbound network
 * payloads, in the network format ({@code CelerisCodecs.wireCodec()}:
 * deflate, so every peer can read it whatever its JVM or platform).
 *
 * <p>Works on the caller's arrays directly with per-thread reusable
 * Deflaters ({@link HeapDeflate}): one compression pass and one copy of the
 * result, with no off-heap staging. The bytes on the wire are the same as in
 * earlier versions, which went through off-heap scratch to produce them.
 */
public final class PayloadCompression {

    private static final int DEFAULT_THRESHOLD_BYTES = 256;
    private static final int DEFAULT_COMPRESSION_LEVEL = 3;

    /**
     * Upper bound on a peer-declared decompressed size. originalSize arrives
     * from the network, so without a cap a malicious peer could make us
     * allocate ~2 GB per packet.
     */
    public static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

    private PayloadCompression() {}

    /** Compresses {@code rawData} using the default threshold (256 bytes) and level (3). */
    public static CompressedPayload compress(byte[] rawData) {
        return compress(rawData, DEFAULT_THRESHOLD_BYTES, DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Compresses {@code rawData} if it is at least {@code thresholdBytes} long
     * and compression actually makes it smaller; otherwise the payload carries
     * a copy of the input, uncompressed. {@code compressionLevel} is on zstd's
     * 1-22 scale, mapped onto deflate's 1-9 (the default, 3, is deflate's fastest).
     */
    public static CompressedPayload compress(byte[] rawData, int thresholdBytes, int compressionLevel) {
        if (rawData.length >= thresholdBytes) {
            byte[] packed = HeapDeflate.compressIfSmaller(rawData, compressionLevel);
            if (packed != null) {
                return new CompressedPayload(packed, true, rawData.length);
            }
        }
        return new CompressedPayload(rawData.clone(), false, rawData.length);
    }

    /**
     * Reverses {@link #compress}, returning the original bytes.
     *
     * @throws IllegalArgumentException if the payload declares more than {@link #MAX_DECOMPRESSED_SIZE} bytes
     * @throws IllegalStateException if the compressed data is malformed or does not decompress to exactly the declared size
     */
    public static byte[] decompress(CompressedPayload payload) {
        if (!payload.compressed()) {
            return payload.data().clone();
        }
        int declared = payload.originalSize();
        if (declared < 0 || declared > MAX_DECOMPRESSED_SIZE) {
            throw new IllegalArgumentException("Celeris payload declares " + declared
                    + " decompressed bytes (max " + MAX_DECOMPRESSED_SIZE + ")");
        }
        byte[] result = new byte[declared];
        HeapDeflate.inflateExactly(payload.data(), result);
        return result;
    }
}
