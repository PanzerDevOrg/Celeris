# Changelog

Notes for each release are in [`docs/changelogs/`](./docs/changelogs/). This file lists changes as they land,
grouped as Added, Changed, Deprecated, Removed, Fixed and Security. Versions follow SemVer.

## [Unreleased]

### Added

- The Gradle task `verifyJniSymbols` (part of `check`): every `native` method must have an exported `Java_*` symbol,
  and every exported `Java_*` symbol must come from a `native` method. It runs the local-only
  `scripts/verify-jni-symbols.sh` and is skipped where that script is absent (`scripts/` and `docs/audit/` are no
  longer tracked by git).
- `.github/workflows/release.yml`: release gate for `v*` tags (no tracked binaries, native self-test, JNI symbols,
  JNI, parity and hostile-input tests with the native library required).
- `VectorBackendParityTest`, and dirty-queue, entity-bridge regression tests.
- `PayloadCompression.validate`, `TransitDeltaRecorder.droppedOperations`, `LongRingDirtyQueue.close`.

### Fixed

- `LongRingDirtyQueue` no longer loses or duplicates marks while its presence bitset grows, spills to an overflow list
  instead of dropping updates when the ring is full, and is `AutoCloseable` (its off-heap ring was never freed).
- Received payloads are no longer inflated on the network thread and discarded: only the header is checked. Sending a
  payload above the 1 MiB wire cap fails at the call site.
- `SimdVectorBackend.dotProduct` uses `Math.fma` in its tail, like the scalar backend. Dot product and sum may still
  differ in the last bits between backends; the documentation no longer says otherwise.
- Heap memory backend: stale handles are rejected (generation tags), offsets beyond `int` throw, `byteBuffer` views are
  little-endian. The MPSC ring validates its source before reserving a slot, so a failing publish cannot wedge it.
- JNI terrain checks `close()` in `solidClass`/`friction`; the FFM body batch bounds-checks slots; the JNI kernel
  probes every native entry point when it loads, so a library missing a symbol falls back instead of failing at the
  first tick.
- Entity bridge: no vanilla tick for an entity unregistered earlier in the tick, and a write made during the deferred
  pass is no longer overwritten by the sync-out.
- Transit: `insert` rejects NaN and infinite speeds; the delta recorder starts a new message instead of dropping an
  operation too far from the base tick. `flushDirty` survives a listener that marks again; `TopologicalDAGSolver`
  resets its table when a listener throws; `ScreenTracker` counts atomically.
- Native tests fail instead of skipping when `REQUIRE_LIBRARIES=1`; the hostile-input test now covers JNI.
- CI: the sanitizer workflow triggers on `main` (and weekly), the release gate pins the same build-logic commit as
  `ci.yml`.
- Removed the unused `compression_algo` setting.

- The JNI entry points of the physics kernel and of the zstd shim were exported as `Java_com_bichal_*`, but the Java
  classes are `net.bichal.mods.celeris.*`. `System.load` succeeded and the native methods could not link, so
  Celeris silently used FFM or the Java path. The fix renames the entry points to `Java_net_bichal_*`. Released jars
  from 0.3.1.1 still carry the old names until the next release is built.

### Changed

- Gradle must run on JDK 25 (Fabric Loom 1.18.3 needs it), even for the 1.21.x nodes. Documentation to follow.
