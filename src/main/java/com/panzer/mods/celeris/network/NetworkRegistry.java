package com.panzer.mods.celeris.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//? >1.21.6 {
/*import net.neoforged.neoforge.client.network.ClientPacketDistributor;
*///?} else
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;

// Maintainer note: this file's branching (PacketDistributor vs ClientPacketDistributor,
// DirectionalPayloadHandler vs separate handler args) was confirmed against the
// version-pinned docs.neoforged.net/docs/{version}/networking/payload page for each
// Stonecutter target, not the unversioned/latest docs -- the 26.1 shape differs (separate
// ClientPacketDistributor, RegisterClientPayloadHandlersEvent). Re-verify against the
// version-pinned page, not the latest one, before changing this file.
/**
 * Registers {@link CompressedPayload} with NeoForge's payload system and
 * handles receiving it on both sides. External mods don't call {@link
 * #register} directly (Celeris does that at startup) -- use {@link
 * #sendToServer} / {@link #sendToPlayer} to actually send data.
 */
public final class NetworkRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkRegistry.class);
    private static final String NETWORK_VERSION = "1";

    private NetworkRegistry() {
    }

    /** Wires up {@link CompressedPayload}'s handlers. Called once by Celeris during mod startup. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);

        registrar.playBidirectional(
                CompressedPayload.TYPE,
                CompressedPayload.STREAM_CODEC,

                //? if >1.21.6 {
                /*NetworkRegistry::handleOnServer,
                NetworkRegistry::handleOnClient
                *///?} else {
                new DirectionalPayloadHandler<>(
                        NetworkRegistry::handleOnClient,
                        NetworkRegistry::handleOnServer
                )
                //?}
        );
    }

    private static void handleOnClient(CompressedPayload payload, IPayloadContext context) {
        handlePayload("client", payload, context);
    }

    private static void handleOnServer(CompressedPayload payload, IPayloadContext context) {
        handlePayload("server", payload, context);
    }

    private static void handlePayload(String side, CompressedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            byte[] decompressed;
            try {
                decompressed = PayloadCompression.decompress(payload);
            } catch (RuntimeException e) {
                // Peer-controlled input: a malformed or oversized payload is
                // dropped, never allowed to throw into the main thread's task queue.
                LOGGER.warn("Celeris ({}): dropping invalid payload ({} bytes on wire): {}",
                        side, payload.data().length, e.toString());
                return;
            }
            LOGGER.debug("Celeris ({}): received {} bytes ({} on wire, compressed={})",
                    side, decompressed.length, payload.data().length, payload.compressed());
        });
    }

    /** Compresses {@code rawData} and sends it to the server. Client-side only. */
    public static void sendToServer(byte[] rawData) {
        //? >1.21.6 {
        /*ClientPacketDistributor.sendToServer(PayloadCompression.compress(rawData));
        *///?} else
        PacketDistributor.sendToServer(PayloadCompression.compress(rawData));
    }

    /** Compresses {@code rawData} and sends it to one player. Server-side only. */
    public static void sendToPlayer(ServerPlayer player, byte[] rawData) {
        PacketDistributor.sendToPlayer(player, PayloadCompression.compress(rawData));
    }
}
