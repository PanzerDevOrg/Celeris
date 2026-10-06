package com.panzer.mods.celeris.mixin.network;

import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend;
import com.panzer.mods.celeris.util.SegmentAccess;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.lang.foreign.MemorySegment;

/**
 * Exposes a zero-copy {@link MemorySegment} view of a direct {@link FriendlyByteBuf}
 * for any mod code that wants off-heap access without a heap byte[] round-trip.
 * <p>
 * Only available when Celeris is running on the FFM backend (see
 * {@link CelerisRuntime}) -- in pure-Java compat mode there is no
 * {@code MemorySegment} to hand back, so this returns {@code null} exactly
 * as it already does today for a non-direct or fragmented buffer. Callers
 * of {@link SegmentAccess} must already handle a null result for that
 * reason, so compat mode doesn't add a new failure path here, it just
 * makes the existing one more common.
 * <p>
 * Does NOT intercept or replace packet encoding/compression -- that already happens
 * through the sanctioned {@code CustomPacketPayload}/{@code StreamCodec} route (see
 * {@code CelerisNetworking}/{@code CelerisPayloadCodec}). An earlier draft attempted
 * write/read compression hooks directly on this mixin; those were removed as dead
 * code once the payload-based pipeline made them redundant.
 */
@SuppressWarnings({"Since15", "UnusedMixin", "preview", "RedundantSuppression"})
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin implements SegmentAccess {

    @Unique
    private MemorySegment celeris$cachedSegment;
    @Unique
    private boolean celeris$segmentLookupAttempted;

    @Override
    @SuppressWarnings("resource")
    public MemorySegment celeris$asMemorySegment() {
        if (!(CelerisRuntime.backend() instanceof FfmMemoryBackend)) {
            return null;
        }

        ByteBuf self = (ByteBuf) (Object) this;
        if (!self.isDirect() || self.nioBufferCount() != 1) {
            return null;
        }
        if (!celeris$segmentLookupAttempted) {
            celeris$cachedSegment = MemorySegment.ofBuffer(self.nioBuffer());
            celeris$segmentLookupAttempted = true;
        }
        return celeris$cachedSegment;
    }
}
