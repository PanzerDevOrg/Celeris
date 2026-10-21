# Handoff: item logistics base (branch claude/project-thread-limk8e)

Status: phases 1-3 committed (payload lifecycle, observation, transit solver). Next: 4 persistence/bulk, 5 client state, 6 thread contract, 7 example/docs/0.3.0.
Build: `source tools/setup-env.sh; CELERIS_MIRROR=1 tools/gradle-retry.sh :1.21.1:test -Ppanzer.loaders=neoforge` (baseline: only CodecSmokeTest fails, native zstd absent).
Decisions: ids monotonic per graph; a graph with a PayloadMigrator or listener retains segments rebuilt to the same shape (same id/payload), plain graphs keep the old behaviour.
Decisions: transit items live in per-direction SoA queues (forward = nodeA->nodeB); arrivals routed in canonical segment order (sorted copy rebuilt on `structureVersion()` change).
Decisions: orphaned items (block removed) wait until the next solver tick, then go to TransitMigrator.LostSink.
Measured (TransitBench): 100k items, 200-block loops 101 us vs 261 us objects; 20-block loops tie. SIMD advance == scalar advance (not the bottleneck).
Fixed on the way: updateConnections on an interior node left a stale segment; SIMDPipelineSolverImpl copied the list and reallocated capacities each tick.
Touched: graph/segmented/*, pipeline/continuous/SIMDPipelineSolverImpl, pipeline/transit/*, tests Network*Migration, SegmentGraphObservation, TransitSolver, benchmarks/src/bench/TransitBench, tools/.
Pending: phases 4-7, PR.
