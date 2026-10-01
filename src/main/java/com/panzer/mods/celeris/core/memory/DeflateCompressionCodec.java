package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Pure-JDK fallback codec used when {@code CelerisRuntime} selects the heap
 * backend (no FFM available), so the native zstd path -- and with it
 * {@code ZstdCodec} -- is never loaded at all.
 *
 * <p>Compression ratio and throughput are both worse than zstd (Deflate is
 * an older, less efficient algorithm, and {@link Deflater}/{@link Inflater}
 * require staging through heap {@code byte[]} since they have no
 * off-heap-buffer entry point) -- this is the expected, documented cost of
 * compat mode, not a bug. It requires no preview features, no incubator
 * modules, and no native library at all, only {@code java.util.zip}, which
 * has shipped unchanged since Java 1.1.
 *
 * <p>Not a general zstd-compatible codec: bytes compressed by {@link
 * ZstdCompressionCodec} cannot be decompressed here and vice versa. This is
 * fine because a given running instance of Celeris only ever uses one codec
 * for its whole lifetime (whichever {@code CelerisRuntime} selected at
 * startup) -- there is no scenario where the two need to interoperate on
 * the same compressed bytes.
 */
@SuppressWarnings("JavadocReference")
final class DeflateCompressionCodec implements CompressionCodec {

    @Override
    public long compressBound(long srcSize) {
        // Deflate's worst case (incompressible input) adds a small fixed
        // overhead per Deflater's own documented bound; zlib's classic
        // formula is srcLen + srcLen/1000 + 12, rounded up generously here
        // since callers only need an upper bound to size a buffer, not a
        // tight one.
        return srcSize + (srcSize / 1000) + 64;
    }

    @Override
    public long compress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize, int level) {
        byte[] src = readToHeap(backend, srcHandle, srcOffset, srcSize);

        Deflater deflater = new Deflater(mapLevel(level));
        try {
            deflater.setInput(src);
            deflater.finish();

            byte[] scratch = new byte[(int) Math.min(dstCapacity, Integer.MAX_VALUE)];
            int written = deflater.deflate(scratch);
            if (!deflater.finished()) {
                throw new IllegalStateException(
                        "Celeris compat-mode compression overflowed the destination buffer "
                                + "(capacity=" + dstCapacity + ", srcSize=" + srcSize + ") -- "
                                + "compressBound() under-estimated the worst case for this input");
            }
            backend.copyFromHeap(dstHandle, 0, scratch, 0, written);
            return written;
        } finally {
            deflater.end();
        }
    }

    @Override
    public long decompress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize) {
        byte[] src = readToHeap(backend, srcHandle, srcOffset, srcSize);

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(src);
            byte[] scratch = new byte[(int) Math.min(dstCapacity, Integer.MAX_VALUE)];
            int written = inflater.inflate(scratch);
            backend.copyFromHeap(dstHandle, 0, scratch, 0, written);
            return written;
        } catch (DataFormatException e) {
            throw new IllegalStateException("Celeris compat-mode decompression failed: malformed input", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] readToHeap(MemoryBackend backend, long handle, long offset, long length) {
        byte[] heap = new byte[(int) length];
        backend.copyToHeap(handle, offset, heap, 0, (int) length);
        return heap;
    }

    /**
     * Celeris's compression level knob is defined against zstd's 1-22
     * scale; Deflater's is 0-9. Maps proportionally rather than clamping,
     * so a caller's "max compression" request (zstd level 22) still lands
     * on Deflater's actual max (9) instead of silently under-compressing.
     */
    @SuppressWarnings("MathClampMigration")
    private static int mapLevel(int zstdLevel) {
        int clamped = Math.max(1, Math.min(22, zstdLevel));
        int mapped = (int) Math.round(clamped / 22.0 * Deflater.BEST_COMPRESSION);
        return Math.max(Deflater.BEST_SPEED, Math.min(Deflater.BEST_COMPRESSION, mapped));
    }

    @Override
    public String name() {
        return "deflate";
    }
}
