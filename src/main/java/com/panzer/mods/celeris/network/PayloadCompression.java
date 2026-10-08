package com.panzer.mods.celeris.network;

import com.panzer.mods.celeris.config.CelerisSettings;
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

    /**
     * Upper bound on a peer-declared decompressed size. originalSize arrives
     * from the network, so without a cap a malicious peer could make us
     * allocate ~2 GB per packet.
     */
    public static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

    private PayloadCompression() {}

    /**
     * Compresses {@code rawData} using the default threshold (256 bytes) and the
     * user's level ({@link CelerisSettings#compressionLevel()}: deflate 0-9, where
     * 0 sends it uncompressed).
     */
    public static CompressedPayload compress(byte[] rawData) {
        return compressAtDeflateLevel(rawData, CelerisSettings.compressionLevel(), true);
    }

    /**
     * Compresses {@code rawData} if it is at least {@code thresholdBytes} long
     * and compression actually makes it smaller; otherwise the payload carries
     * a copy of the input, uncompressed. {@code compressionLevel} is on zstd's
     * 1-22 scale, mapped onto deflate's 1-9 (1-3 all map to deflate 1).
     */
    public static CompressedPayload compress(byte[] rawData, int thresholdBytes, int compressionLevel) {
        return compress(rawData, thresholdBytes, compressionLevel, true);
    }

    /**
     * {@link #compress(byte[])} that takes ownership of {@code rawData}: when the
     * data goes out uncompressed, the payload carries {@code rawData} itself
     * instead of a copy. The caller must not modify or reuse the array afterwards
     * (the payload may still be waiting to be encoded, or be handed as-is to an
     * in-memory connection).
     */
    public static CompressedPayload compressOwned(byte[] rawData) {
        return compressAtDeflateLevel(rawData, CelerisSettings.compressionLevel(), false);
    }

    /** {@link #compress(byte[], int, int)} that takes ownership of {@code rawData}; see {@link #compressOwned(byte[])}. */
    public static CompressedPayload compressOwned(byte[] rawData, int thresholdBytes, int compressionLevel) {
        return compress(rawData, thresholdBytes, compressionLevel, false);
    }

    private static CompressedPayload compress(byte[] rawData, int thresholdBytes, int compressionLevel, boolean copy) {
        byte[] packed = rawData.length >= thresholdBytes ? HeapDeflate.compressIfSmaller(rawData, compressionLevel) : null;
        return packed != null ? new CompressedPayload(packed, true, rawData.length) : uncompressed(rawData, copy);
    }

    private static CompressedPayload compressAtDeflateLevel(byte[] rawData, int deflateLevel, boolean copy) {
        byte[] packed = deflateLevel > 0 && rawData.length >= DEFAULT_THRESHOLD_BYTES
                ? HeapDeflate.deflateIfSmaller(rawData, deflateLevel) : null;
        return packed != null ? new CompressedPayload(packed, true, rawData.length) : uncompressed(rawData, copy);
    }

    private static CompressedPayload uncompressed(byte[] rawData, boolean copy) {
        return new CompressedPayload(copy ? rawData.clone() : rawData, false, rawData.length);
    }

    /**
     * Reverses {@link #compress}, returning the original bytes.
     *
     * @throws IllegalArgumentException if the payload declares more than {@link #MAX_DECOMPRESSED_SIZE} bytes
     * @throws IllegalStateException if the compressed data is malformed or does not decompress to exactly the declared size
     */
    public static byte[] decompress(CompressedPayload payload) {
        return decompress(payload, true);
    }

    /**
     * {@link #decompress} that takes ownership of the payload's array: for an
     * uncompressed payload it returns {@code payload.data()} itself instead of a
     * copy. The payload must not be used again afterwards (not re-sent, not
     * decompressed a second time), since the returned array is its data.
     *
     * @throws IllegalArgumentException if the payload declares more than {@link #MAX_DECOMPRESSED_SIZE} bytes
     * @throws IllegalStateException if the compressed data is malformed or does not decompress to exactly the declared size
     */
    public static byte[] decompressOwned(CompressedPayload payload) {
        return decompress(payload, false);
    }

    private static byte[] decompress(CompressedPayload payload, boolean copy) {
        if (!payload.compressed()) {
            return copy ? payload.data().clone() : payload.data();
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
