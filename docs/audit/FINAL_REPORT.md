# Audit report: Celeris

**Status:** historical record, not normative. [`GROUND_TRUTH.md`](./GROUND_TRUTH.md) describes the repository as it
is; this file records what the audit asked for and what was done. Statuses:
`implemented`, `partial`, `not applicable` (the audit's premise does not hold for this repository), `not started`.

Branch `ccr-a954e01c-a4i778`. Commit references are on that branch.

## Verdicts on the audit's claims

| Audit claim | Verdict | Evidence |
|---|---|---|
| Celeris is a full physics engine | **Wrong in scope.** `physics/` integrates SoA item-like bodies with vanilla's movement rules and a spatial-hash broadphase. There are no constraints or solver | `src/main/java/.../physics/` |
| Celeris is split into `celeris.api/common/neoforge/fabric/native/benchmarks` modules | **Wrong.** One Gradle project, multiplied by Stonecutter into one node per Minecraft version and loader; `native/` is a CMake project | `settings.gradle.kts`, `stonecutter.gradle.kts` |
| Java 21, 22 and 23 are the targets | **Wrong.** Java 21 for 1.21.x, Java 25 for 26.x | `mod.stonecutter.properties.toml` |
| Off-heap memory uses `Arena.ofAuto()` in loops, and needs `ofConfined` per thread | **Not applicable as stated.** No `ofAuto` in the code; FFM uses `Arena.ofShared()`, and backends are chosen once | `grep -rn "Arena\." src` |
| The native kernel needs fixed-point and a `CP_MODE_IEEE` flag | **Not applicable.** `CP_MODE_VANILLA` (bit-identical to Java) and `CP_MODE_FUSED` (FMA) exist; no fixed-point code | `native/include/celeris_physics.h` |
| GPU offload is part of the engine | **Not applicable.** Nothing in the repository runs on the GPU | — |
| Benchmarks come from JMH in the repository | **Wrong.** The numbers in `docs/benchmarks.md` come from a harness that is not in this repository; the repository has JUnit `@Tag("benchmark")` tests that print | `src/test/java/*BenchmarkTest.java` |

## Workstreams

| WS | Item | Status | What exists now |
|---|---|---|---|
| WS-1 | Ground truth | implemented | `docs/audit/GROUND_TRUTH.md`, including build verification and two defects |
| WS-1 | Baseline benchmarks (`baseline.json`) | not started | No JMH; the JUnit benchmarks print only |
| WS-2 | README and public docs in the new structure | not started | README unchanged |
| WS-3 | Javadoc and Doxygen | not started | — |
| WS-4 | Java hot path (FFM, `Arena`, `Linker`) | partial | Checked: no `ofAuto`, no `reinterpret` in the code, kernel downcalls already critical (`isTrivial` on 21, `critical(false)` on 25). No per-tick allocation gate yet |
| WS-5 | Native kernel (ISA dispatch, determinism) | partial | ISA dispatch, hidden visibility and the exported API already exist; `cp_selftest` and `cp_fuzz` pass. Missing: `[[nodiscard]]`, `std::experimental::simd`, the `CP_MODE_FIXED` mode (descoped as optional), the AVX2/AVX-512/NEON speedup measurement |
| WS-6 | ASan, UBSan, fuzzing, JMH gate | partial | `native-sanitizers.yml` runs ASan + UBSan (+ float-cast-overflow) on x86-64 and arm. Missing: CMake sanitizer options, libFuzzer harnesses, TSan, JMH regression gate |
| WS-7 | CI/CD | partial | `release.yml` gate for `v*` tags (native self-test, JNI symbols, JNI tests). Publishing is in the shared `ci.yml`. Missing: CodeQL, cross-compile with Zig, cosign, docs publishing |
| WS-8 | Mixin compatibility | not started | One Java mixin (`FriendlyByteBufMixin`) plus `CelerisMixinPlugin`; no coexistence matrix |
| WS-9 | GPU offload (exploratory) | not applicable | No GPU code |
| WS-10 | JVM tuning guide | not started | — |
| WS-11 | Community files and templates | not started | No `CONTRIBUTING.md`, `SECURITY.md`, issue templates |
| WS-12 | Repository hygiene | partial | `.gitignore` excludes native binaries; `scripts/check-no-binaries.sh`; no `.editorconfig`, spotless or errorprone |
| WS-13 | This report | implemented | this file |

## What changed on this branch

| Change | Where | Verified by |
|---|---|---|
| JNI prefix `Java_com_bichal_*` → `Java_net_bichal_*` (physics kernel and zstd shim) | `native/src/cp_jni.cpp`, `native/zstd/celeris_zstd_jni.h` | `PhysicsJniTest` (asserts the engine is `native-jni-*`) passes in `:1.21.1:test`; `nm -D` |
| `verifyJniSymbols` task and script | `build.gradle.kts`, `scripts/verify-jni-symbols.sh` | Passes on the staged libraries (13 of 13 symbols) |
| No committed binaries, check script | `.gitignore`, `scripts/check-no-binaries.sh` | `git ls-files` finds none |
| Release gate | `.github/workflows/release.yml` | Not run on GitHub from here |
| Ground truth and build verification | `docs/audit/GROUND_TRUTH.md` | `:1.21.1:build` with native libraries: 167 tests, 0 failed |

## Known gaps

- Released jars of 0.3.1.1 carry the `Java_com_bichal_*` names and their native paths do not link until a new release
  is built from this branch.
- `docs/benchmarks.md` cites numbers from a harness that is not in the repository. They cannot be reproduced from
  here until that harness is added.
- Gradle needs JDK 25 even for the 1.21.x nodes. The README still says 21.
- This branch has not been run through CI on GitHub.
