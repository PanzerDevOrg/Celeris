package com.panzer.mods.celeris.core.topology;

/**
 * The single point of the {@code core}/{@code graph}/{@code pipeline} tree
 * that acknowledges a {@link NodeId} started life as a real world position.
 * This class still does not import anything from {@code net.minecraft.*} --
 * it only knows "a packed long that follows the same bit layout as vanilla
 * BlockPos#asLong". The consumer mod's own integration layer (outside this
 * tree, e.g. next to {@code mixin/}) is the one place that actually calls
 * {@code BlockPos#asLong()} and hands the result here.
 *
 * <p>Because {@link NodeId}'s bit layout is defined to match BlockPos's own
 * packing exactly, this conversion is a straight bit-copy -- no shifting,
 * no arithmetic, so it costs nothing beyond the wrapper allocation the
 * {@code record} already elides on hot paths.
 */
public final class NodeIdCodec {

    private NodeIdCodec() {
    }

    /** Wraps an already-packed world position (e.g. {@code BlockPos#asLong()}) as an opaque NodeId. */
    public static NodeId of(long packedWorldPosition) {
        return new NodeId(packedWorldPosition);
    }

    /** Unwraps a NodeId back to the packed long a consumer's BlockPos-shaped API expects. */
    public static long toPackedPosition(NodeId id) {
        return id.raw();
    }
}
