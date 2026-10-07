//? if fabric && >=26.1 {
/*package com.panzer.mods.celeris.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/^* Fabric API 26.1 renamed playC2S/playS2C to serverboundPlay/clientboundPlay. ^/
final class FabricPayloads {
    private FabricPayloads() {
    }

    static void registerBothWays() {
        PayloadTypeRegistry.serverboundPlay().register(CompressedPayload.TYPE, CompressedPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompressedPayload.TYPE, CompressedPayload.STREAM_CODEC);
    }
}
*///?} else if fabric {
/*package com.panzer.mods.celeris.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/^* Fabric API before 26.1: playC2S/playS2C. ^/
final class FabricPayloads {
    private FabricPayloads() {
    }

    static void registerBothWays() {
        PayloadTypeRegistry.playC2S().register(CompressedPayload.TYPE, CompressedPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(CompressedPayload.TYPE, CompressedPayload.STREAM_CODEC);
    }
}
*///?}
