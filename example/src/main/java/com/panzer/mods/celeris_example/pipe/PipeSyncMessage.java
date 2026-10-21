package com.panzer.mods.celeris_example.pipe;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * What a client needs besides the item movements themselves
 * ({@code TransitDelta}): the server tick the movements are relative to, which
 * handle is which item, and the shape of every segment (the deltas only name
 * segments by id). Plain data, no Minecraft types.
 *
 * <p>Layout: {@code flags(byte), serverTick(long), then four counted lists}:
 * item definitions {@code (handle, itemId)}, released handles, added segments
 * {@code (id, count, positions...)} and removed segment ids. {@link #read} checks
 * every count against the bytes that remain and returns null for anything
 * malformed, so a bad message is dropped as a whole.
 */
public record PipeSyncMessage(boolean reset, long serverTick,
                       int[] defHandles, int[] defItems, int[] released,
                       long[] segmentIds, long[][] segmentPositions, long[] removedSegments) {

    /** Positions in one segment worth drawing; longer runs are not sent. */
    public static final int MAX_POSITIONS = 8192;
    /** Bytes in one message. */
    public static final int MAX_BYTES = 1 << 20;
    private static final int MAX_ENTRIES = 1 << 16;

    public boolean isEmpty() {
        return !reset && defHandles.length == 0 && released.length == 0
                && segmentIds.length == 0 && removedSegments.length == 0;
    }

    public byte[] toBytes() {
        int size = 1 + 8 + 4 * 4;
        size += defHandles.length * 8 + released.length * 4 + removedSegments.length * 8;
        for (long[] positions : segmentPositions) {
            size += 8 + 4 + positions.length * 8;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.put((byte) (reset ? 1 : 0));
        out.putLong(serverTick);
        out.putInt(defHandles.length);
        for (int i = 0; i < defHandles.length; i++) {
            out.putInt(defHandles[i]);
            out.putInt(defItems[i]);
        }
        out.putInt(released.length);
        for (int handle : released) {
            out.putInt(handle);
        }
        out.putInt(segmentIds.length);
        for (int i = 0; i < segmentIds.length; i++) {
            out.putLong(segmentIds[i]);
            out.putInt(segmentPositions[i].length);
            for (long position : segmentPositions[i]) {
                out.putLong(position);
            }
        }
        out.putInt(removedSegments.length);
        for (long id : removedSegments) {
            out.putLong(id);
        }
        return out.array();
    }

    public static PipeSyncMessage read(byte[] data) {
        if (data == null || data.length > MAX_BYTES) {
            return null;
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(data);
            boolean reset = in.get() != 0;
            long tick = in.getLong();

            int n = count(in, 8);
            int[] defHandles = new int[n];
            int[] defItems = new int[n];
            for (int i = 0; i < n; i++) {
                defHandles[i] = in.getInt();
                defItems[i] = in.getInt();
            }
            n = count(in, 4);
            int[] released = new int[n];
            for (int i = 0; i < n; i++) {
                released[i] = in.getInt();
            }
            n = count(in, 12);
            long[] ids = new long[n];
            long[][] positions = new long[n][];
            for (int i = 0; i < n; i++) {
                ids[i] = in.getLong();
                int len = in.getInt();
                if (len < 1 || len > MAX_POSITIONS || len > in.remaining() / 8) {
                    return null;
                }
                positions[i] = new long[len];
                for (int j = 0; j < len; j++) {
                    positions[i][j] = in.getLong();
                }
            }
            n = count(in, 8);
            long[] removed = new long[n];
            for (int i = 0; i < n; i++) {
                removed[i] = in.getLong();
            }
            if (in.hasRemaining()) {
                return null;
            }
            return new PipeSyncMessage(reset, tick, defHandles, defItems, released, ids, positions, removed);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return null;
        }
    }

    private static int count(ByteBuffer in, int bytesPerEntry) {
        int n = in.getInt();
        if (n < 0 || n > MAX_ENTRIES || (long) n * bytesPerEntry > in.remaining()) {
            throw new IllegalArgumentException("bad count");
        }
        return n;
    }
}
