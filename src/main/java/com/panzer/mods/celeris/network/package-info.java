/**
 * Optional native-compression layer for network payloads, on top of
 * NeoForge's standard payload API.
 *
 * <p>Start with {@link com.panzer.mods.celeris.network.PayloadCompression}
 * to compress/decompress a {@code byte[]} directly, or {@link
 * com.panzer.mods.celeris.network.NetworkRegistry#sendToServer} / {@link
 * com.panzer.mods.celeris.network.NetworkRegistry#sendToPlayer} to compress
 * and send in one call.
 *
 * <p>This is additive to Vanilla's own transport-level compression, not a
 * replacement for it -- worth reaching for on large, already-structured
 * payloads (bulk world data, big inventory syncs, SIMD results), not every
 * packet.
 *
 * @since 0.1.0
 */
package com.panzer.mods.celeris.network;
