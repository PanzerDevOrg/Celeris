package com.panzer.mods.celeris.graph.segmented;

import java.nio.ByteBuffer;

/**
 * Turns a segment's payload into compact bytes and back, so a network's state
 * can be saved without Minecraft types and rebuilt after a reload. It encodes
 * the payload only: which segment it belongs to is decided by position, not by
 * {@link GraphSegment#id()} (ids restart with every graph), see
 * {@link SegmentStateIO}.
 *
 * <p>Evolving the format: bump {@link #formatVersion()} when the layout
 * changes and keep {@link #read} able to decode every older version it was
 * given.
 */
public interface SegmentStateCodec<T> {

    /** Version {@link #write} produces. */
    int formatVersion();

    /** Upper bound, in bytes, of what {@link #write} emits for this segment. */
    int maxSize(GraphSegment<T> segment);

    /** Appends the payload state of {@code segment}. */
    void write(GraphSegment<T> segment, ByteBuffer out);

    /**
     * Restores state into {@code segment}, replacing what its payload held.
     *
     * @param version  the version the bytes were written with (&lt;= {@link #formatVersion()})
     * @param reversed true when the saved segment ran the opposite way: its A end is this
     *                 segment's B end (graphs may trace a run from either end after a reload)
     * @param in       positioned at the payload and limited to it; throw
     *                 {@link java.nio.BufferUnderflowException} or
     *                 {@link IllegalArgumentException} for malformed data and the record is skipped
     */
    void read(GraphSegment<T> segment, int version, boolean reversed, ByteBuffer in);
}
