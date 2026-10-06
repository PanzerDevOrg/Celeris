package com.panzer.mods.celeris.pipeline.continuous;

import com.panzer.mods.celeris.graph.segmented.SegmentedGraph;
import com.panzer.mods.celeris.pipeline.PipelineSolver;

/**
 * Optional batch-tick strategy over a {@link SegmentedGraph}: gathers
 * per-segment scalar state (flow rate, buffer level, etc.) into flat {@code
 * float[]} arrays via {@link SegmentTickBatch}, then calls {@code
 * VectorOperations} once per batch instead of once per segment. Purely
 * additive over {@link SegmentedGraph} -- a consumer that doesn't need
 * this keeps calling {@code segments()} directly.
 */
public interface SIMDPipelineSolver<T> extends PipelineSolver {

    void bind(SegmentedGraph<T> graph, SegmentTickBatch.Extractor<T> extractor);
}
