# Native libraries

Some parts of Celeris are written in C and C++, because they run much faster there. They are compiled into one library file per system: `.so` on Linux, `.dll` on Windows and `.dylib` on macOS. The library files sit inside the Celeris jar.

## What is inside

| Library | What it is | Source code |
|---|---|---|
| zstd 1.5.7 | Zstandard, a compression library from Meta, released under the BSD 3-Clause license. It is built from the official release, plus a few small Celeris functions so that Java 21 can call it. | [native/zstd](https://github.com/bichal/Celeris/tree/main/native/zstd) |
| Physics kernel | Celeris's own code that moves batches of items and bodies. | [native](https://github.com/bichal/Celeris/tree/main/native) |

## How they are built

- GitHub Actions builds each library on its own system.
- Each release is built from the same tagged commit as its source files.
- Nothing is built by hand, and no compiled library is kept in the repository.

The release workflow packs the libraries into the jars and uploads them. The release page also has a Java only file, with no native parts at all.

## What happens if a library does not load

Celeris does not stop. It uses the Java code instead, and the log says which one it chose. See [Troubleshooting](Troubleshooting).

## Known issue

Version 0.3.1.1 has a naming problem in its native parts. The details and the workaround are in [Troubleshooting](Troubleshooting#known-issue-in-version-0311).
