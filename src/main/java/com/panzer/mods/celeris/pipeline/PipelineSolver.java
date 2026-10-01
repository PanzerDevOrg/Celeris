package com.panzer.mods.celeris.pipeline;

/**
 * Minimal shared contract across BOTH paradigms -- deliberately tiny. This
 * is the "strategy" half of the Strategy pattern the 2.0 design splits
 * out: {@code SegmentedGraph}/{@code DiscreteGraph} answer "what does the
 * network look like"; {@code PipelineSolver} answers "when and how do I
 * process it". A consumer wires one of each together (e.g.
 * {@code SegmentedGraph<FluidPayload>} + {@code SIMDPipelineSolver}, or
 * {@code DiscreteGraph} + {@code EventDrivenPipelineSolver}) but never
 * needs a solver to know which topology paradigm it's driving beyond what
 * its own generic parameter already says.
 */
public interface PipelineSolver {

    /** Advances this pipeline by one server tick. Idempotent no-op if isIdle(). */
    void tick();

    /** True when this pipeline has no pending work -- callers can skip tick() entirely. */
    boolean isIdle();

    String name(); // "SIMD continuous", "scalar continuous (compat)", "event-driven discrete"
}
