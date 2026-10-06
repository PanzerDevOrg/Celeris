package com.panzer.mods.celeris.pipeline.continuous;

import com.panzer.mods.celeris.graph.segmented.GraphSegment;

/**
 * Struct-of-arrays batch of per-segment scalar state (flow rate, buffer
 * level), filled from active segments via {@link Extractor} and passed
 * directly to {@code VectorOperations} -- reuses the existing SIMD layer
 * unmodified rather than each pipeline consumer hand-rolling its own
 * gather loop.
 *
 * <p>Not thread-safe and not retained across ticks beyond its backing
 * arrays -- {@link SIMDPipelineSolverImpl} owns one instance and reuses it,
 * growing the backing arrays geometrically as the active segment count
 * grows, exactly like {@code MpscRingBuffer}'s capacity discipline.
 */
public final class SegmentTickBatch {

    private float[] flowRates = new float[64];
    private float[] bufferLevels = new float[64];
    private boolean[] overflowMask = new boolean[64];
    private int size;

    public int size() {
        return size;
    }

    public float[] flowRates() {
        return flowRates;
    }

    public float[] bufferLevels() {
        return bufferLevels;
    }

    public boolean[] overflowMask() {
        return overflowMask;
    }

    public void reset() {
        size = 0;
    }

    public void add(float flowRate, float bufferLevel) {
        ensureCapacity(size + 1);
        flowRates[size] = flowRate;
        bufferLevels[size] = bufferLevel;
        size++;
    }

    private void ensureCapacity(int required) {
        if (required <= flowRates.length) {
            return;
        }
        int newCapacity = Integer.highestOneBit(required - 1) << 1;
        float[] grownFlow = new float[newCapacity];
        float[] grownBuffer = new float[newCapacity];
        boolean[] grownMask = new boolean[newCapacity];
        System.arraycopy(flowRates, 0, grownFlow, 0, flowRates.length);
        System.arraycopy(bufferLevels, 0, grownBuffer, 0, bufferLevels.length);
        System.arraycopy(overflowMask, 0, grownMask, 0, overflowMask.length);
        flowRates = grownFlow;
        bufferLevels = grownBuffer;
        overflowMask = grownMask;
    }

    /** Extracts a segment's payload-specific scalar state into this batch, and writes any tick result back. */
    public interface Extractor<T> {

        /** Appends this segment's current (flowRate, bufferLevel) to {@code batch} via {@link #add}. */
        void gather(GraphSegment<T> segment, SegmentTickBatch batch);

        /** Applies the post-tick buffer level at {@code index} back onto the segment's payload. */
        void scatter(GraphSegment<T> segment, int index, SegmentTickBatch batch);
    }
}
