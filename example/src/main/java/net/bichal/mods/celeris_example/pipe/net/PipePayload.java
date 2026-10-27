package net.bichal.mods.celeris_example.pipe.net;

import net.bichal.mods.celeris.network.CompressedPayload;
import net.bichal.mods.celeris_example.CelerisExample;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * One message to the client: either a {@code PipeSyncMessage} or a Celeris
 * {@code TransitDelta}, compressed with Celeris's {@code PayloadCompression}
 * ({@link CompressedPayload} holds the bytes and says whether they were).
 * Celeris's own channel hands received bytes to nobody, so the example
 * registers a payload type of its own.
 */
public record PipePayload(boolean delta, CompressedPayload body) implements CustomPacketPayload {

    public static final Type<PipePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CelerisExample.MOD_ID, "pipe_items"));

    public static final StreamCodec<ByteBuf, PipePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PipePayload::delta,
            ByteBufCodecs.byteArray(CompressedPayload.MAX_COMPRESSED_PAYLOAD_SIZE), p -> p.body().data(),
            ByteBufCodecs.BOOL, p -> p.body().compressed(),
            ByteBufCodecs.VAR_INT, p -> p.body().originalSize(),
            (delta, data, compressed, size) -> new PipePayload(delta, new CompressedPayload(data, compressed, size)));

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
