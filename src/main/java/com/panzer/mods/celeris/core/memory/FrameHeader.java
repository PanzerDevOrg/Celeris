package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

/**
 * Fixed 5-byte frame header prefixing every packet payload in a channel.
 *
 * <p>Layout: {@code [flags:1][decompressedSize:4 (little-endian, unaligned)]}.
 *
 * <p>Operates through {@link MemoryBackend} rather than a raw
 * {@code MemorySegment}, so the same header format is read/written
 * identically whether the engine is running on the FFM fast path or the
 * pure-Java compat path -- see {@code CelerisRuntime}.
 */
public final class FrameHeader {

    public static final int HEADER_BYTES = 5;

    private static final long FLAGS_OFFSET = 0L;
    private static final long SIZE_OFFSET = 1L;

    private static final byte FLAG_COMPRESSED = 1;

    private FrameHeader() {
    }

    public static void write(MemoryBackend backend, long handle, long offset, boolean compressed, int decompressedSize) {
        byte flags = (byte) (compressed ? FLAG_COMPRESSED : 0);
        backend.setByte(handle, offset + FLAGS_OFFSET, flags);
        backend.setInt(handle, offset + SIZE_OFFSET, decompressedSize);
    }

    public static boolean isCompressed(MemoryBackend backend, long handle, long offset) {
        return (flags(backend, handle, offset) & FLAG_COMPRESSED) != 0;
    }

    public static int decompressedSize(MemoryBackend backend, long handle, long offset) {
        return backend.getInt(handle, offset + SIZE_OFFSET);
    }

    private static byte flags(MemoryBackend backend, long handle, long offset) {
        return backend.getByte(handle, offset + FLAGS_OFFSET);
    }
}
