package com.panzer.mods.celeris.pipeline.continuous;

import com.panzer.mods.celeris.api.simd.VectorOperations;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraph;

/**
 * Default {@link SIMDPipelineSolver}: on each {@link #tick()}, gathers
 * every currently-bound segment's scalar state into a {@link
 * SegmentTickBatch} via the consumer-supplied {@link
 * SegmentTickBatch.Extractor}, advances the whole batch through {@link
 * VectorOperations} in one call, then scatters the results back onto each
 * segment's payload -- one vectorized pass over N segments instead of N
 * scalar payload updates.
 *
 * <p>The actual per-tick transfer model (how flow rate feeds into buffer
 * level) is deliberately the simplest possible additive step here --
 * {@code bufferLevel[i] += flowRate[i]} via {@code
 * VectorOperations#addInPlace}, with {@code
 * VectorOperations#thresholdMask} flagging any segment that overflowed a
 * fixed capacity -- because the real transfer semantics (item queues,
 * fluid units, energy buffers) are consumer-specific and layered on top by
 * whatever supplies the {@link SegmentTickBatch.Extractor}; this class
 * only owns the "batch instead of per-segment" execution strategy.
 */
public final class SIMDPipelineSolverImpl<T> implements SIMDPipelineSolver<T> {

    private static final float DEFAULT_CAPACITY = 1.0f;

    private final SegmentTickBatch batch = new SegmentTickBatch();
    private float[] capacityScratch = new float[64];

    private SegmentedGraph<T> graph;
    private SegmentTickBatch.Extractor<T> extractor;
    @SuppressWarnings("unchecked")
    private GraphSegment<T>[] active = (GraphSegment<T>[]) new GraphSegment[64];

    @Override
    public void bind(SegmentedGraph<T> graph, SegmentTickBatch.Extractor<T> extractor) {
        this.graph = graph;
        this.extractor = extractor;
    }

    @Override
    public void tick() {
        if (graph == null || extractor == null) {
            return;
        }

        int segmentCount = graph.segmentCount();
        if (segmentCount == 0) {
            return;
        }
        if (active.length < segmentCount) {
            active = java.util.Arrays.copyOf(active, Integer.highestOneBit(segmentCount - 1) << 1);
        }

        batch.reset();
        for (int i = 0; i < segmentCount; i++) {
            GraphSegment<T> segment = graph.segmentByIndex(i);
            active[i] = segment;
            extractor.gather(segment, batch);
        }

        int count = batch.size();
        float[] flowRates = batch.flowRates();
        float[] bufferLevels = batch.bufferLevels();
        boolean[] overflowMask = batch.overflowMask();

        VectorOperations.addInPlace(bufferLevels, flowRates, count);

        float[] capacities = capacityFor(count);
        VectorOperations.greaterThanMask(bufferLevels, capacities, overflowMask, count);

        for (int i = 0; i < count; i++) {
            extractor.scatter(active[i], i, batch);
            active[i] = null;
        }
    }

    @Override
    public boolean isIdle() {
        return graph == null || graph.segmentCount() == 0;
    }

    @Override
    public String name() {
        return "SIMD continuous";
    }

    private float[] capacityFor(int count) {
        if (capacityScratch.length >= count) {
            java.util.Arrays.fill(capacityScratch, 0, count, DEFAULT_CAPACITY);
            return capacityScratch;
        }
        capacityScratch = new float[Integer.highestOneBit(count - 1) << 1];
        java.util.Arrays.fill(capacityScratch, 0, count, DEFAULT_CAPACITY);
        return capacityScratch;
    }
}
