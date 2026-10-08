//? if neoforge {
package com.panzer.mods.celeris.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

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

    private static final String NETWORK_VERSION = "1";

    private NetworkRegistry() {
    }

    /**
     * Wires up {@link CompressedPayload}'s handlers. Called once by Celeris during mod startup.
     *
     * <p>The channel is optional: NeoForge refuses a connection when either side
     * has a required channel the other lacks (and refuses vanilla peers outright),
     * so a required channel would make every player install Celeris to join a
     * server that has it, and keep Celeris users off servers without it.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        // Handled on the network thread (NeoForge's default is the main one): see PayloadReceiver.
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION).optional().executesOn(HandlerThread.NETWORK);

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
        PayloadReceiver.receive("client", payload);
    }

    private static void handleOnServer(CompressedPayload payload, IPayloadContext context) {
        PayloadReceiver.receive("server", payload);
    }

    /** Whether the server this client is connected to has Celeris's channel. Client-side only. */
    public static boolean serverHasChannel() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(CompressedPayload.TYPE);
    }

    /** Whether {@code player}'s client has Celeris's channel. Server-side only. */
    public static boolean playerHasChannel(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(CompressedPayload.TYPE);
    }

    /**
     * Compresses {@code rawData} and sends it to the server. Client-side only.
     * Does nothing when the server has no Celeris ({@link #serverHasChannel}).
     */
    public static void sendToServer(byte[] rawData) {
        if (!serverHasChannel()) {
            return;
        }
        //? >1.21.6 {
        /*ClientPacketDistributor.sendToServer(PayloadCompression.compress(rawData));
        *///?} else
        PacketDistributor.sendToServer(PayloadCompression.compress(rawData));
    }

    /**
     * Compresses {@code rawData} and sends it to one player. Server-side only.
     * Does nothing when the player has no Celeris ({@link #playerHasChannel}).
     */
    public static void sendToPlayer(ServerPlayer player, byte[] rawData) {
        if (!playerHasChannel(player)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, PayloadCompression.compress(rawData));
    }
}
//?} else {
/*package com.panzer.mods.celeris.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/^*
 * Registers {@link CompressedPayload} with Fabric API's networking and
 * handles receiving it on both sides; the same public methods as on NeoForge.
 * External mods don't call {@link #register} / {@link #registerClient}
 * directly (Celeris does that at startup) -- use {@link #sendToServer} /
 * {@link #sendToPlayer} to actually send data.
 *
 * <p>Fabric channels are optional: players without Celeris can join a server
 * that has it, and the reverse; the send methods check the other side first.
 ^/
public final class NetworkRegistry {

    private NetworkRegistry() {
    }

    /^* Registers the payload type both ways and the server-side receiver. Called once by Celeris at startup. ^/
    public static void register() {
        FabricPayloads.registerBothWays();
        ServerPlayNetworking.registerGlobalReceiver(CompressedPayload.TYPE,
                (payload, context) -> PayloadReceiver.receive("server", payload));
    }

    /^* Registers the client-side receiver. Called once by Celeris's client entrypoint. ^/
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(CompressedPayload.TYPE,
                (payload, context) -> PayloadReceiver.receive("client", payload));
    }

    /^* Whether the server this client is connected to has Celeris's channel. Client-side only. ^/
    public static boolean serverHasChannel() {
        return ClientPlayNetworking.canSend(CompressedPayload.TYPE);
    }

    /^* Whether {@code player}'s client has Celeris's channel. Server-side only. ^/
    public static boolean playerHasChannel(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, CompressedPayload.TYPE);
    }

    /^*
     * Compresses {@code rawData} and sends it to the server. Client-side only.
     * Does nothing when the server has no Celeris ({@link #serverHasChannel}).
     ^/
    public static void sendToServer(byte[] rawData) {
        if (serverHasChannel()) {
            ClientPlayNetworking.send(PayloadCompression.compress(rawData));
        }
    }

    /^*
     * Compresses {@code rawData} and sends it to one player. Server-side only.
     * Does nothing when the player has no Celeris ({@link #playerHasChannel}).
     ^/
    public static void sendToPlayer(ServerPlayer player, byte[] rawData) {
        if (playerHasChannel(player)) {
            ServerPlayNetworking.send(player, PayloadCompression.compress(rawData));
        }
    }
}
*///?}
