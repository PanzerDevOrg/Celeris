# Native libraries

Celeris ships two kinds of native libraries, both built from public source by GitHub Actions on each platform's own
runner, from the same tagged commit as every file published here:

- **zstd 1.5.7** (Meta's Zstandard, BSD-3-Clause), compiled from the official release tarball
  ([`zstd-1.5.7.tar.gz`](https://github.com/facebook/zstd/releases/tag/v1.5.7), SHA-256 `eb33e51f...6fa3`, checked
  by the build), plus a few small functions of Celeris's own that let Java 21 call it without extra flags:
  [`native/zstd`](https://github.com/bichal/Celeris/tree/master/native/zstd).
- **The physics kernel**, Celeris's own C++ code: [`native/`](https://github.com/bichal/Celeris/tree/master/native).

The release workflow builds them, packs the jars and uploads them here; GitHub releases also carry a Java-only file
with no native code at all.

[Back to Home](Home.md)
