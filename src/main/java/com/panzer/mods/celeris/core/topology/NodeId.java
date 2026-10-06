package com.panzer.mods.celeris.core.topology;

import com.panzer.mods.celeris.util.math.BranchlessTables;

/**
 * Opaque 64-bit identifier for a node in any Celeris network graph (segmented
 * or discrete). Wraps a packed world-space block position but the core
 * NEVER unpacks it for graph logic -- adjacency, hashing and equality all
 * operate on the raw long. Only {@link NodeIdCodec} (or a consumer's own
 * integration layer) ever needs to interpret the bits as X/Y/Z.
 *
 * <p>This is the one seam between Celeris's engine core and "a position in
 * some voxel world" -- deliberately a single opaque value type, not
 * {@code net.minecraft.core.BlockPos}, so nothing under core/, graph/ or
 * pipeline/ needs Minecraft on its classpath. A consumer mod's integration
 * layer is the only code that ever calls {@link NodeIdCodec#of}.
 *
 * <p>Bit layout (LSB first): {@code [x:26][z:26][y:12]}, signed
 * two's-complement per field, identical layout to vanilla BlockPos#asLong
 * so NodeIdCodec is a straight bit-copy with no arithmetic when the
 * consumer's positions already come from BlockPos.
 *
 * <p>Why a {@code record} over a raw {@code long} in every core signature:
 * for a single-{@code long} record the JIT reliably inlines/escape-analyzes
 * it away on hot paths (same reasoning the project already applies to
 * {@code GraphNodeState}, itself a record of a single {@code int}), and the
 * strong typing avoids confusing a NodeId with an unrelated {@code long}
 * handle (e.g. a {@code MemoryBackend} allocation handle) in a method
 * signature -- a class of bug the raw-long alternative cannot catch at
 * compile time.
 */
public record NodeId(long raw) {

    private static final int Y_BITS = 12;
    private static final int Z_BITS = 26;
    private static final int X_BITS = 26;

    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;
    private static final long X_MASK = (1L << X_BITS) - 1L;

    private static final int Y_SHIFT = 0;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + Z_BITS;

    public static NodeId pack(int x, int y, int z) {
        return new NodeId(packRaw(x, y, z));
    }

    /** Packed-long form of {@link #pack} for allocation-free internals. */
    public static long packRaw(int x, int y, int z) {
        return ((long) x & X_MASK) << X_SHIFT
                | ((long) z & Z_MASK) << Z_SHIFT
                | ((long) y & Y_MASK) << Y_SHIFT;
    }

    public static int xOf(long raw) {
        return (int) (raw << (64 - X_SHIFT - X_BITS) >> (64 - X_BITS));
    }

    public static int yOf(long raw) {
        return (int) (raw << (64 - Y_SHIFT - Y_BITS) >> (64 - Y_BITS));
    }

    public static int zOf(long raw) {
        return (int) (raw << (64 - Z_SHIFT - Z_BITS) >> (64 - Z_BITS));
    }

    /** Packed-long form of {@link #step}: no record instance created. */
    public static long stepRaw(long raw, int directionIndex) {
        return packRaw(
                xOf(raw) + BranchlessTables.stepX(directionIndex),
                yOf(raw) + BranchlessTables.stepY(directionIndex),
                zOf(raw) + BranchlessTables.stepZ(directionIndex));
    }

    public int x() {
        return xOf(raw);
    }

    public int y() {
        return yOf(raw);
    }

    public int z() {
        return zOf(raw);
    }

    /** Steps this id by one block in {@code directionIndex} (BranchlessTables ordinal order). */
    public NodeId step(int directionIndex) {
        return new NodeId(stepRaw(raw, directionIndex));
    }
}
