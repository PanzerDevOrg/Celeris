package com.panzer.mods.celeris.graph.segmented;

import com.panzer.mods.celeris.core.format.StateFormat;
import com.panzer.mods.celeris.core.topology.NodeId;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.function.Predicate;

/**
 * Saves and restores the payload state of a whole {@link SegmentedGraph}
 * through a {@link SegmentStateCodec}, keyed by position.
 *
 * <p>Layout: {@code magic, version, count} then per segment
 * {@code startRaw, secondRaw, endRaw (longs), positionCount (int), payloadLength (int), payload}.
 * A saved segment is found again by where it starts, the block after that and
 * where it ends, so it matches whatever id the rebuilt graph gave it and
 * whichever end it is traced from. If the run no longer has the same shape
 * (a neighbouring chunk is not loaded yet, a pipe was added), its record is
 * not applied but handed to the {@link Unmatched} callback, so the caller can
 * keep it and offer it again after the graph changes.
 *
 * <p>Reading never trusts the bytes: sizes are capped, a truncated or
 * corrupt record is counted and skipped, nothing is thrown.
 */
public final class SegmentStateIO {

    public static final int MAGIC = StateFormat.magic("CSST");
    /** Layout version of this container (the payload has its own, the codec's). */
    public static final int CONTAINER_VERSION = 1;

    /** More records than this in one blob is treated as corrupt. */
    public static final int MAX_RECORDS = 1 << 22;
    /** A payload bigger than this is treated as corrupt. */
    public static final int MAX_PAYLOAD_BYTES = 1 << 24;

    private static final int RECORD_FIXED_BYTES = 8 * 3 + 4 + 4;
    private static final int HEADER_BYTES = 16;

    private SegmentStateIO() {
    }

    /** Callback for records that found no segment of the same shape. {@code payload} is a read-only copy of the record's bytes. */
    @FunctionalInterface
    public interface Unmatched {
        void unmatched(long startRaw, long secondRaw, long endRaw, int positionCount, ByteBuffer payload);
    }

    /** Outcome of a {@link #read}. {@code rejected} means the whole blob was refused (wrong magic or newer version). */
    public record Result(int applied, int unmatched, int malformed, boolean rejected) {
    }

    /** Bytes {@link #write} may use for the segments passing {@code filter} (null = all). */
    public static <T> int maxBytes(SegmentedGraph<T> graph, SegmentStateCodec<T> codec, Predicate<GraphSegment<T>> filter) {
        long total = HEADER_BYTES;
        for (int i = 0, n = graph.segmentCount(); i < n; i++) {
            GraphSegment<T> s = graph.segmentByIndex(i);
            if (filter == null || filter.test(s)) {
                total += RECORD_FIXED_BYTES + codec.maxSize(s);
            }
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalStateException("State too large for one buffer");
        }
        return (int) total;
    }

    /**
     * Writes the state of the segments passing {@code filter} (null = all), in a
     * canonical order (by start position), so equal states give equal bytes.
     * Returns how many segments were written.
     */
    public static <T> int write(SegmentedGraph<T> graph, SegmentStateCodec<T> codec, ByteBuffer out,
                                Predicate<GraphSegment<T>> filter) {
        java.util.List<GraphSegment<T>> chosen = new java.util.ArrayList<>();
        for (int i = 0, n = graph.segmentCount(); i < n; i++) {
            GraphSegment<T> s = graph.segmentByIndex(i);
            if (filter == null || filter.test(s)) {
                chosen.add(s);
            }
        }
        chosen.sort((a, b) -> {
            int c = Long.compare(a.nodeA().raw(), b.nodeA().raw());
            return c != 0 ? c : Long.compare(a.rawPositionAt(1), b.rawPositionAt(1));
        });

        StateFormat.writeHeader(out, MAGIC, CONTAINER_VERSION);
        out.putInt(codec.formatVersion());
        out.putInt(chosen.size());
        for (GraphSegment<T> s : chosen) {
            out.putLong(s.nodeA().raw());
            out.putLong(s.rawPositionAt(1));
            out.putLong(s.nodeB().raw());
            out.putInt(s.positionCount());
            int lengthAt = out.position();
            out.putInt(0);
            codec.write(s, out);
            out.putInt(lengthAt, out.position() - lengthAt - 4);
        }
        return chosen.size();
    }

    /** Restores state written by {@link #write} into the matching segments of {@code graph}. */
    public static <T> Result read(SegmentedGraph<T> graph, SegmentStateCodec<T> codec, ByteBuffer in, Unmatched unmatched) {
        int container = StateFormat.readHeader(in, MAGIC, CONTAINER_VERSION);
        if (container < 0 || in.remaining() < 8) {
            return new Result(0, 0, 0, true);
        }
        int payloadVersion = in.getInt();
        int count = in.getInt();
        if (payloadVersion < 0 || payloadVersion > codec.formatVersion()
                || count < 0 || count > MAX_RECORDS || (long) count * RECORD_FIXED_BYTES > in.remaining()) {
            return new Result(0, 0, 0, true);
        }

        int applied = 0;
        int missing = 0;
        int malformed = 0;
        for (int r = 0; r < count; r++) {
            if (in.remaining() < RECORD_FIXED_BYTES) {
                malformed += count - r;
                break;
            }
            long start = in.getLong();
            long second = in.getLong();
            long end = in.getLong();
            int positions = in.getInt();
            int length = in.getInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES || length > in.remaining()) {
                malformed += count - r;
                break;
            }
            ByteBuffer payload = in.slice().limit(length).order(in.order());
            in.position(in.position() + length);

            GraphSegment<T> segment = positions >= 2 ? locate(graph, start, second, positions) : null;
            int orientation = segment == null ? -1 : orientation(segment, start, second, end, positions);
            if (orientation < 0) {
                missing++;
                if (unmatched != null) {
                    unmatched.unmatched(start, second, end, positions, payload.asReadOnlyBuffer().order(in.order()));
                }
                continue;
            }
            try {
                codec.read(segment, payloadVersion, orientation == 1, payload);
                applied++;
            } catch (BufferUnderflowException | IllegalArgumentException e) {
                malformed++;
            }
        }
        return new Result(applied, missing, malformed, false);
    }

    private static <T> GraphSegment<T> locate(SegmentedGraph<T> graph, long start, long second, int positions) {
        for (int d = 0; d < 6; d++) {
            if (NodeId.stepRaw(start, d) == second) {
                GraphSegment<T> s = graph.branchAt(new NodeId(start), d);
                return s != null && s.positionCount() == positions ? s : null;
            }
        }
        return null;
    }

    /** 0 = the saved segment ran the same way, 1 = the opposite way, -1 = a different shape. */
    private static <T> int orientation(GraphSegment<T> s, long start, long second, long end, int positions) {
        if (s.nodeA().raw() == start && s.rawPositionAt(1) == second && s.nodeB().raw() == end) {
            return 0;
        }
        if (s.nodeB().raw() == start && s.rawPositionAt(positions - 2) == second && s.nodeA().raw() == end) {
            return 1;
        }
        return -1;
    }
}
