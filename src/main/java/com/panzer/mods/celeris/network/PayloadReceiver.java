package com.panzer.mods.celeris.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What both loaders do with a received {@link CompressedPayload}: inflate and
 * check it on the thread the loader hands it over on, with no further hop to
 * the game thread. On NeoForge the handlers are registered for the network
 * thread, so inflating up to {@link PayloadCompression}'s limit never stalls a
 * tick or a frame.
 */
final class PayloadReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("celeris");

    private PayloadReceiver() {
    }

    static void receive(String side, CompressedPayload payload) {
        byte[] decompressed;
        try {
            decompressed = PayloadCompression.decompress(payload);
        } catch (RuntimeException e) {
            // Peer-controlled input: a malformed or oversized payload is dropped.
            LOGGER.warn("Celeris ({}): dropping invalid payload ({} bytes on wire): {}",
                    side, payload.data().length, e.toString());
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Celeris ({}): received {} bytes ({} on wire, compressed={})",
                    side, decompressed.length, payload.data().length, payload.compressed());
        }
    }
}
