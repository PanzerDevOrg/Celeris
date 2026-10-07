package com.panzer.mods.celeris.util;

import java.nio.file.Path;
import java.util.List;

/**
 * Test-only accessor for {@link NativeLibraries}' extraction with an explicit
 * cache root, so tests never touch the real per-user cache.
 */
public final class NativeLibrariesTestAccess {
    private NativeLibrariesTestAccess() {}

    public static Path extract(String resource, String fileName, Path cacheRoot) {
        return NativeLibraries.extract(resource, fileName, List.of(cacheRoot));
    }
}
