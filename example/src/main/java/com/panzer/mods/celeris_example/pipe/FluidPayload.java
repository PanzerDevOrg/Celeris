package com.panzer.mods.celeris_example.pipe;

import com.panzer.mods.celeris.graph.segmented.GraphSegment;

/**
 * Per-segment state for the pipe example: a constant inflow rate and a
 * buffer level that fills over time. This is exactly the kind of
 * consumer-specific payload {@link GraphSegment}
 * is generic over -- a real mod would put an item queue, a fluid amount,
 * or an energy buffer here instead.
 */
public final class FluidPayload {

    private static final float DEFAULT_FLOW_RATE = 0.01f;

    @SuppressWarnings("FieldCanBeLocal")
    private final float flowRate = DEFAULT_FLOW_RATE;
    private float bufferLevel;
    private boolean overflowing;

    public float flowRate() {
        return flowRate;
    }

    public float bufferLevel() {
        return bufferLevel;
    }

    public boolean isOverflowing() {
        return overflowing;
    }

    /** Written back by {@link PipeNetworkManager}'s {@code SegmentTickBatch.Extractor} after each SIMD batch tick. */
    public void setBufferLevel(float bufferLevel) {
        this.bufferLevel = bufferLevel;
    }

    public void setOverflowing(boolean overflowing) {
        this.overflowing = overflowing;
    }
}
