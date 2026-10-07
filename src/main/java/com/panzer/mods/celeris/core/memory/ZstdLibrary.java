package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.util.NativeLibraries;

import java.nio.file.Path;

/** Where the bundled libzstd (with Celeris's JNI entry points) lives on disk, for both bindings. */
final class ZstdLibrary {

    private ZstdLibrary() {
    }

    /** Names inside the jar, as set by [natives.zstd] in mod.stonecutter.properties.toml. */
    static String fileName() {
        if (NativeLibraries.isWindows()) {
            return "libzstd.dll";
        }
        if (NativeLibraries.isMac()) {
            return "libzstd.dylib";
        }
        return "libzstd.so.1";
    }

    static Path path() {
        return NativeLibraries.extract(fileName());
    }
}
