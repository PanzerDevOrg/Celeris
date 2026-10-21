package com.panzer.mods.celeris.core.format;

import java.nio.ByteBuffer;

/**
 * Helpers for binary formats that must keep loading older data: every blob
 * starts with a 4-byte magic (which format it is) and a 4-byte version. A
 * reader accepts any version up to the one it knows and must keep decoding the
 * older layouts; a version from the future is refused instead of misread.
 */
public final class StateFormat {

    /** {@link #readHeader} result: the magic did not match, so this is not the expected format. */
    public static final int BAD_MAGIC = -1;
    /** {@link #readHeader} result: written by a newer version than the reader knows. */
    public static final int TOO_NEW = -2;
    /** {@link #readHeader} result: not enough bytes for a header. */
    public static final int TRUNCATED = -3;

    private StateFormat() {
    }

    public static void writeHeader(ByteBuffer out, int magic, int version) {
        out.putInt(magic);
        out.putInt(version);
    }

    /**
     * Reads and checks the header at the buffer's position. Returns the version
     * (&gt;= 0) with the position moved past the header, or one of the negative
     * constants with the position unchanged.
     */
    public static int readHeader(ByteBuffer in, int magic, int maxVersion) {
        if (in.remaining() < 8) {
            return TRUNCATED;
        }
        int at = in.position();
        if (in.getInt(at) != magic) {
            return BAD_MAGIC;
        }
        int version = in.getInt(at + 4);
        if (version < 0 || version > maxVersion) {
            return TOO_NEW;
        }
        in.position(at + 8);
        return version;
    }

    /** Builds a magic from four ASCII characters, e.g. {@code magic("CSST")}. */
    public static int magic(String fourChars) {
        if (fourChars.length() != 4) {
            throw new IllegalArgumentException("magic needs exactly 4 characters");
        }
        return fourChars.charAt(0) << 24 | fourChars.charAt(1) << 16 | fourChars.charAt(2) << 8 | fourChars.charAt(3);
    }
}
