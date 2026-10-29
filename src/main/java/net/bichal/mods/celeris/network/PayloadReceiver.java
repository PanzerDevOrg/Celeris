package net.bichal.mods.celeris.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What both loaders do with a received {@link CompressedPayload}: check its header
 * on the thread the loader hands it over on. The body is <em>not</em> inflated here:
 * nothing in Celeris consumes the bytes, and inflating up to
 * {@link PayloadCompression#MAX_DECOMPRESSED_SIZE} for every packet would let any
 * client make the server burn CPU and memory for a result that is thrown away. A mod
 * that registers its own handler decompresses when it needs the data
 * ({@link PayloadCompression#decompress}).
 */
final class PayloadReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("celeris");

    private PayloadReceiver() {
    }

    static void receive(String side, CompressedPayload payload) {
        try {
            PayloadCompression.validate(payload);
        } catch (RuntimeException e) {
            // Peer-controlled input: a payload with an impossible header is dropped.
            LOGGER.warn("Celeris ({}): dropping invalid payload ({} bytes on wire): {}",
                    side, payload.data().length, e.toString());
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Celeris ({}): received {} bytes ({} on wire, compressed={})",
                    side, payload.originalSize(), payload.data().length, payload.compressed());
        }
    }
}
