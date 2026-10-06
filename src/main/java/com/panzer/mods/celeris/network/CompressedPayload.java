package com.panzer.mods.celeris.network;

import com.panzer.mods.celeris.Celeris;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.NotNull;

//? >1.21.10 {
/*import net.minecraft.resources.Identifier;
*///?} else
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link CustomPacketPayload} carrying a Celeris-compressed byte blob --
 * the wire format {@link PayloadCompression} and {@link NetworkRegistry}
 * send/receive. You won't normally construct this directly; get one from
 * {@link PayloadCompression#compress}.
 *
 * <p>This rides on top of NeoForge's standard payload API rather than
 * touching Netty directly, and is an optional, additive layer on top of
 * Vanilla's own transport-level compression -- worth it for large,
 * already-structured payloads (bulk world data, big inventory syncs, SIMD
 * results) where zstd beats zlib's ratio/speed tradeoff. See README.md,
 * "Module 1.3 redesign", for the full design reasoning.
 */
public record CompressedPayload(byte[] data, boolean compressed,
                                int originalSize) implements CustomPacketPayload {

    //? >1.21.10 {
    /*public static final Type<CompressedPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Celeris.MOD_ID, "compressed_blob"));
    *///?} else {
    public static final Type<CompressedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Celeris.MOD_ID, "compressed_blob"));
    //?}

    /** Hard cap on wire size for this payload type -- {@link PayloadCompression#compress} results larger than this will fail to encode. */
    public static final int MAX_COMPRESSED_PAYLOAD_SIZE = 1024 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, CompressedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.byteArray(MAX_COMPRESSED_PAYLOAD_SIZE), CompressedPayload::data,
                    ByteBufCodecs.BOOL, CompressedPayload::compressed,
                    ByteBufCodecs.VAR_INT, CompressedPayload::originalSize,
                    CompressedPayload::new
            );

    @Override
    public @NotNull Type<CompressedPayload> type() {
        return TYPE;
    }
}
