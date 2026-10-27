package net.bichal.mods.celeris.network;

import net.bichal.mods.celeris.api.transit.TransitDelta;

/**
 * Compression and safe reading of {@link TransitDelta} messages for the wire:
 * {@link PayloadCompression} on the way out, and on the way in a bounded
 * decompression followed by full validation. Anything oversized, corrupt or
 * malformed is dropped (the methods return null/false), never thrown at the
 * network thread and never half applied.
 */
public final class TransitDeltaPackets {

    /** A compressed message bigger than this is dropped without being inflated. */
    public static final int MAX_COMPRESSED_BYTES = TransitDelta.MAX_BYTES;

    private TransitDeltaPackets() {
    }

    public static CompressedPayload encode(byte[] message) {
        return PayloadCompression.compress(message);
    }

    /**
     * Decompresses and applies a received message. Returns false when the payload
     * is too big, does not inflate to its declared size, or is not a valid delta.
     */
    public static boolean decode(CompressedPayload payload, TransitDelta.Listener listener) {
        if (payload == null || payload.data().length > MAX_COMPRESSED_BYTES
                || payload.originalSize() < 0 || payload.originalSize() > TransitDelta.MAX_BYTES) {
            return false;
        }
        byte[] raw;
        try {
            raw = PayloadCompression.decompress(payload);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
        return TransitDelta.read(raw, raw.length, listener);
    }
}
