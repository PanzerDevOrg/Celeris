package net.bichal.mods.celeris.pipeline.transit;

import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentStateCodec;

import java.nio.ByteBuffer;

/**
 * Compact binary form of a {@link TransitPayload}: per direction a count and,
 * per item, 12 bytes ({@code progress}, {@code speed} as floats, {@code handle}
 * as int). The handles are the consumer's own ids; persisting what they stand
 * for is the consumer's job. Use with {@code SegmentStateIO}.
 */
public final class TransitStateCodec implements SegmentStateCodec<TransitPayload> {

    public static final int VERSION = 1;
    private static final int MAX_ITEMS_PER_QUEUE = 1 << 16;
    private static final int ITEM_BYTES = 12;

    @Override
    public int formatVersion() {
        return VERSION;
    }

    @Override
    public int maxSize(GraphSegment<TransitPayload> segment) {
        TransitPayload p = segment.payload();
        return 8 + (p.forward().size() + p.backward().size()) * ITEM_BYTES;
    }

    @Override
    public void write(GraphSegment<TransitPayload> segment, ByteBuffer out) {
        writeQueue(segment.payload().forward(), out);
        writeQueue(segment.payload().backward(), out);
    }

    @Override
    public void read(GraphSegment<TransitPayload> segment, int version, boolean reversed, ByteBuffer in) {
        TransitPayload payload = segment.payload();
        TransitQueue first = reversed ? payload.backward() : payload.forward();
        TransitQueue second = reversed ? payload.forward() : payload.backward();
        // Decode both before touching the payload so a malformed record leaves it as it was.
        Items firstItems = readItems(in);
        Items secondItems = readItems(in);
        float length = segment.length();
        fill(first, firstItems, length);
        fill(second, secondItems, length);
    }

    private static void writeQueue(TransitQueue q, ByteBuffer out) {
        out.putInt(q.size());
        for (int i = 0; i < q.size(); i++) {
            out.putFloat(q.progressAt(i));
            out.putFloat(q.speedAt(i));
            out.putInt(q.handleAt(i));
        }
    }

    private record Items(float[] progress, float[] speed, int[] handle) {
    }

    private static Items readItems(ByteBuffer in) {
        int count = in.getInt();
        if (count < 0 || count > MAX_ITEMS_PER_QUEUE || (long) count * ITEM_BYTES > in.remaining()) {
            throw new IllegalArgumentException("Bad item count " + count);
        }
        Items items = new Items(new float[count], new float[count], new int[count]);
        for (int i = 0; i < count; i++) {
            float progress = in.getFloat();
            float speed = in.getFloat();
            if (!Float.isFinite(progress) || !Float.isFinite(speed)) {
                throw new IllegalArgumentException("Non-finite item");
            }
            items.progress[i] = progress;
            items.speed[i] = speed;
            items.handle[i] = in.getInt();
        }
        return items;
    }

    private static void fill(TransitQueue queue, Items items, float length) {
        queue.clear();
        for (int i = 0; i < items.handle.length; i++) {
            float progress = Math.min(Math.max(items.progress[i], 0f), length);
            queue.insertSorted(items.handle[i], items.speed[i], progress);
        }
    }
}
