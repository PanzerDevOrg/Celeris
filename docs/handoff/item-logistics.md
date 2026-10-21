# Handoff: item logistics base (branch claude/project-thread-limk8e)

Status: all 7 phases committed, one commit per phase (plus tools/ and the example). Version bumped to 0.3.0 with docs/changelogs/0.3.0.md. Not tagged, not published.
Build: `source tools/setup-env.sh; CELERIS_MIRROR=1 tools/gradle-retry.sh build --continue -Ppanzer.loaders=neoforge` (baseline on every version: only CodecSmokeTest fails, 3 tests, native zstd absent in the sandbox).
Decisions: ids monotonic per graph; a graph with a PayloadMigrator or listener retains segments rebuilt to the same shape (same id/payload), plain graphs keep the old behaviour.
Decisions: transit items live in per-direction SoA queues (forward = nodeA->nodeB); arrivals routed in canonical segment order (sorted copy rebuilt on `structureVersion()` change).
Decisions: orphaned items (block removed) wait until the next solver tick, then go to TransitMigrator.LostSink.
Decisions: single-threaded by contract (documented on SegmentedGraph); GraphSegmentImpl.payload is no longer volatile; no async simulation was built (proposal in the PR).
Measured: TransitBench 100k items, 200-block loops 101 us vs 261 us objects; 20-block loops tie; SIMD advance == scalar. BulkBuildBench 10k pipes: line 8966 ms -> 2.7 ms, grid 45 -> 3.9 ms.
Fixed on the way: updateConnections on an interior node left a stale segment; findNearestJunction used -1 (a real position) as "none"; SIMDPipelineSolverImpl copied the list and reallocated capacities each tick.
Example (example/.../pipe): compiled and unit-tested (PipeSyncMessageTest), NOT run in a game: no client was available. Items in flight are not saved there.
Next: review the PR; decide on async advance, on persisting items in the example, and when to tag 0.3.0.
