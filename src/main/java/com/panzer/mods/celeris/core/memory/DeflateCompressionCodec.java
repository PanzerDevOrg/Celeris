package com.panzer.mods.celeris.core.memory;

import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate codec: the network format ({@link CelerisCodecs#wireCodec()}) and
 * the local fallback when native zstd is unavailable. Needs no preview
 * features, no incubator modules and no library of Celeris's own, only
 * {@code java.util.zip} (native zlib inside the JDK).
 *
 * <p>Deflaters and Inflaters are reused per thread ({@link HeapDeflate}), and
 * when the backend offers {@link MemoryBackend#byteBuffer} views (the heap
 * backend's direct buffers) zlib reads and writes that memory directly; on
 * FFM and Unsafe the data goes through per-thread scratch arrays instead,
 * still without allocating per call.
 *
 * <p>Not interchangeable with {@link ZstdCompressionCodec}: each reads only
 * its own format. A running instance picks one local codec for its whole
 * lifetime, and the network always uses deflate, so the two never meet.
 */
final class DeflateCompressionCodec implements CompressionCodec {

    @Override
    public long compressBound(long srcSize) {
        // zlib's deflateBound for the default zlib wrapper is a little under
        // srcLen + srcLen/1000 + 12; rounded up generously, since callers only
        // need an upper bound to size a buffer.
        return srcSize + (srcSize / 1000) + 64;
    }

    @Override
    public long compress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize, int level) {
        int srcLength = Math.toIntExact(srcSize);
        int capacity = (int) Math.min(dstCapacity, Integer.MAX_VALUE);
        HeapDeflate.State state = HeapDeflate.state();
        Deflater deflater = state.deflater(HeapDeflate.deflateLevel(level));
        try {
            ByteBuffer srcView = backend.byteBuffer(srcHandle, srcOffset, srcLength);
            if (srcView != null) {
                deflater.setInput(srcView);
            } else {
                byte[] in = state.in(srcLength);
                backend.copyToHeap(srcHandle, srcOffset, in, 0, srcLength);
                deflater.setInput(in, 0, srcLength);
            }
            deflater.finish();

            int written;
            ByteBuffer dstView = backend.byteBuffer(dstHandle, 0L, capacity);
            if (dstView != null) {
                while (!deflater.finished() && dstView.hasRemaining()) {
                    if (deflater.deflate(dstView) == 0) {
                        break;
                    }
                }
                written = dstView.position();
            } else {
                byte[] out = state.out(capacity);
                written = 0;
                while (!deflater.finished() && written < capacity) {
                    int n = deflater.deflate(out, written, capacity - written);
                    if (n == 0) {
                        break;
                    }
                    written += n;
                }
                backend.copyFromHeap(dstHandle, 0L, out, 0, written);
            }
            if (!deflater.finished()) {
                throw new IllegalStateException("Celeris deflate output overflowed the destination buffer (capacity="
                        + dstCapacity + ", srcSize=" + srcSize + ")");
            }
            return written;
        } finally {
            deflater.reset(); // also drops the reference to the input buffer
        }
    }

    @Override
    public long decompress(MemoryBackend backend, long dstHandle, long dstCapacity, long srcHandle, long srcOffset, long srcSize) {
        int srcLength = Math.toIntExact(srcSize);
        int capacity = (int) Math.min(dstCapacity, Integer.MAX_VALUE);
        HeapDeflate.State state = HeapDeflate.state();
        Inflater inflater = state.inflater;
        try {
            ByteBuffer srcView = backend.byteBuffer(srcHandle, srcOffset, srcLength);
            if (srcView != null) {
                inflater.setInput(srcView);
            } else {
                byte[] in = state.in(srcLength);
                backend.copyToHeap(srcHandle, srcOffset, in, 0, srcLength);
                inflater.setInput(in, 0, srcLength);
            }

            int written;
            ByteBuffer dstView = backend.byteBuffer(dstHandle, 0L, capacity);
            if (dstView != null) {
                while (!inflater.finished() && dstView.hasRemaining()) {
                    if (inflater.inflate(dstView) == 0) {
                        break;
                    }
                }
                written = dstView.position();
            } else {
                byte[] out = state.out(capacity);
                written = 0;
                while (!inflater.finished() && written < capacity) {
                    int n = inflater.inflate(out, written, capacity - written);
                    if (n == 0) {
                        break;
                    }
                    written += n;
                }
                backend.copyFromHeap(dstHandle, 0L, out, 0, written);
            }
            if (!inflater.finished()) {
                // Output full with data left over, or the stream simply stops early.
                if (written == capacity && inflater.inflate(state.probe, 0, 1) != 0) {
                    throw new IllegalStateException("Celeris deflate data decompresses to more than the destination's "
                            + dstCapacity + " bytes");
                }
                if (!inflater.finished()) {
                    throw new IllegalStateException("Celeris deflate data is truncated after " + written + " bytes");
                }
            }
            return written;
        } catch (DataFormatException e) {
            throw new IllegalStateException("Celeris deflate decompression failed: malformed input", e);
        } finally {
            inflater.reset();
        }
    }

    @Override
    public String name() {
        return "deflate";
    }
}
