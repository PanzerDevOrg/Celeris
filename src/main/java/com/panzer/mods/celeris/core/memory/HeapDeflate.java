package com.panzer.mods.celeris.core.memory;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate on heap arrays with per-thread reusable state: one
 * {@link Deflater} per level in use, one {@link Inflater}, and scratch
 * arrays. Creating a Deflater allocates and initializes about 256 KB of zlib
 * state, more than compressing a typical packet costs, so reusing them is the
 * main saving here. Used by {@code PayloadCompression} for the network format
 * and by {@link DeflateCompressionCodec}.
 *
 * <p>The format is the JDK's default zlib stream, exactly what earlier
 * versions produced, so older peers read it and vice versa. Deflaters and
 * Inflaters free their native memory through their own cleaners when a thread
 * (and so its state) goes away.
 */
public final class HeapDeflate {

    /** Scratch arrays larger than this are used once and dropped, not kept per thread. */
    private static final int MAX_RETAINED_SCRATCH = 1 << 20;

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private HeapDeflate() {
    }

    /**
     * Celeris's compression level knob is defined against zstd's 1-22 scale;
     * Deflater's is 0-9. Maps proportionally rather than clamping, so a
     * caller's "max compression" request (zstd level 22) still lands on
     * Deflater's actual max (9) instead of silently under-compressing.
     */
    @SuppressWarnings("MathClampMigration")
    public static int deflateLevel(int zstdLevel) {
        int clamped = Math.max(1, Math.min(22, zstdLevel));
        int mapped = (int) Math.round(clamped / 22.0 * Deflater.BEST_COMPRESSION);
        return Math.max(Deflater.BEST_SPEED, Math.min(Deflater.BEST_COMPRESSION, mapped));
    }

    /**
     * Deflates {@code src} and returns the compressed bytes, or {@code null}
     * if they would not be smaller than the input. Gives zlib exactly
     * {@code src.length - 1} bytes of room, so incompressible input stops
     * early instead of being compressed in full only to be thrown away.
     */
    public static byte[] compressIfSmaller(byte[] src, int zstdLevel) {
        int limit = src.length - 1;
        if (limit <= 0) {
            return null;
        }
        State state = STATE.get();
        Deflater deflater = state.deflater(deflateLevel(zstdLevel));
        try {
            deflater.setInput(src);
            deflater.finish();
            byte[] out = state.out(limit);
            int written = 0;
            while (!deflater.finished() && written < limit) {
                int n = deflater.deflate(out, written, limit - written);
                if (n == 0) {
                    break;
                }
                written += n;
            }
            return deflater.finished() ? Arrays.copyOf(out, written) : null;
        } finally {
            deflater.reset();
        }
    }

    /**
     * Inflates {@code src} into all of {@code dst}, requiring the stream to
     * decompress to exactly {@code dst.length} bytes.
     *
     * @throws IllegalStateException if the data is malformed, truncated, or longer than {@code dst}
     */
    public static void inflateExactly(byte[] src, byte[] dst) {
        State state = STATE.get();
        Inflater inflater = state.inflater;
        try {
            inflater.setInput(src);
            int written = 0;
            while (written < dst.length) {
                int n = inflater.inflate(dst, written, dst.length - written);
                if (n == 0) {
                    break;
                }
                written += n;
            }
            if (written == dst.length && !inflater.finished() && inflater.inflate(state.probe, 0, 1) != 0) {
                throw new IllegalStateException("Celeris deflate data decompresses to more than the declared "
                        + dst.length + " bytes");
            }
            if (written != dst.length || !inflater.finished()) {
                throw new IllegalStateException("Celeris deflate data is truncated: " + written + " of "
                        + dst.length + " declared bytes");
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("Celeris deflate decompression failed: malformed input", e);
        } finally {
            inflater.reset();
        }
    }

    static State state() {
        return STATE.get();
    }

    /** Per-thread deflate state. Not thread-safe; only ever reached through {@link #STATE}. */
    static final class State {
        private final Deflater[] deflaters = new Deflater[Deflater.BEST_COMPRESSION + 1];
        final Inflater inflater = new Inflater();
        final byte[] probe = new byte[1];
        private byte[] in = new byte[0];
        private byte[] out = new byte[0];

        /** A reset Deflater at {@code level}: one per level, so a level never has to change mid-stream. */
        Deflater deflater(int level) {
            Deflater d = deflaters[level];
            if (d == null) {
                d = new Deflater(level);
                deflaters[level] = d;
            }
            return d;
        }

        byte[] in(int size) {
            if (in.length >= size) {
                return in;
            }
            byte[] grown = new byte[size];
            if (size <= MAX_RETAINED_SCRATCH) {
                in = grown;
            }
            return grown;
        }

        byte[] out(int size) {
            if (out.length >= size) {
                return out;
            }
            byte[] grown = new byte[size];
            if (size <= MAX_RETAINED_SCRATCH) {
                out = grown;
            }
            return grown;
        }
    }
}
