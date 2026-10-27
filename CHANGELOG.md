# Changelog

Notes for each release are in [`docs/changelogs/`](./docs/changelogs/). This file lists changes as they land,
grouped as Added, Changed, Deprecated, Removed, Fixed and Security. Versions follow SemVer.

## [Unreleased]

### Added

- `scripts/verify-jni-symbols.sh` and the Gradle task `verifyJniSymbols` (part of `check`): every `native` method
  must have an exported `Java_*` symbol, and every exported `Java_*` symbol must come from a `native` method.
- `scripts/check-no-binaries.sh`: fails when git tracks a native binary. The `.gitignore` also excludes `*.so`,
  `*.dll`, `*.dylib` and objects.
- `.github/workflows/release.yml`: release gate for `v*` tags (native self-test, JNI symbols, JNI tests).
- `docs/audit/GROUND_TRUTH.md` and `docs/audit/FINAL_REPORT.md`: what the code is, what the audit assumed, and what is
  done.

### Fixed

- The JNI entry points of the physics kernel and of the zstd shim were exported as `Java_com_bichal_*`, but the Java
  classes are `net.bichal.mods.celeris.*`. `System.load` succeeded and the native methods could not link, so
  Celeris silently used FFM or the Java path. The fix renames the entry points to `Java_net_bichal_*`. Released jars
  from 0.3.1.1 still carry the old names until the next release is built.

### Changed

- Gradle must run on JDK 25 (Fabric Loom 1.18.3 needs it), even for the 1.21.x nodes. Documentation to follow.
