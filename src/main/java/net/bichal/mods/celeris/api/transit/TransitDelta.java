package net.bichal.mods.celeris.api.transit;

import net.bichal.mods.celeris.core.format.StateFormat;

import java.util.Arrays;

/**
 * Compact wire format describing what changed for transported items, so a
 * client can animate them with {@link TransitMotion} between messages instead
 * of receiving positions every tick. One message is a batch of operations:
 *
 * <ul>
 *   <li><b>enter</b>: an item appears on a segment (item, segment, direction, progress, speed);</li>
 *   <li><b>leave</b>: it is gone (delivered, lost);</li>
 *   <li><b>move</b>: it continues on another segment (a junction was crossed, or the network was rebuilt);</li>
 *   <li><b>speed</b>: its speed changed (progress at that moment is included, so the client re-bases).</li>
 * </ul>
 *
 * Every operation carries the server tick it happened at; the client keeps
 * the item from then on. The item key is the consumer's own handle; segment
 * ids are {@code GraphSegment.id()}, which the consumer maps to block
 * positions with its own segment table.
 *
 * <p>Layout: {@code magic, version, baseTick (varlong), opCount (varint)} then the operations,
 * each {@code opcode|forward<<7, item (varint), [segment (varlong)], [progress, speed (floats)], tickDelta (varint)}.
 *
 * <p>Decoding never trusts the sender: sizes and counts are capped, floats must be finite and in range,
 * and the whole message is validated before the listener hears anything, so a malformed message is dropped
 * as a whole ({@link #read} returns false) and never half applied. Compress the bytes with
 * {@code PayloadCompression} (see {@code TransitDeltaPackets}).
 */
public final class TransitDelta {

    public static final int MAGIC = StateFormat.magic("CTDL");
    public static final int VERSION = 1;

    /** Most operations in one message. */
    public static final int MAX_OPS = 4096;
    /** Most bytes in one message (before compression). */
    public static final int MAX_BYTES = 1 << 18;
    /** Largest speed accepted, in blocks per tick. */
    public static final float MAX_SPEED = 64f;
    /** Largest progress accepted, in blocks. */
    public static final float MAX_PROGRESS = 1_000_000f;
    /** Largest gap between the base tick and an operation. */
    public static final int MAX_TICK_DELTA = 0xFFFF;

    private static final int OP_ENTER = 1;
    private static final int OP_LEAVE = 2;
    private static final int OP_MOVE = 3;
    private static final int OP_SPEED = 4;
    private static final int FORWARD_BIT = 0x80;

    private TransitDelta() {
    }

    /** Receives the operations of a validated message, in order. */
    public interface Listener {
        void enter(int item, long segment, boolean forward, float progress, float speed, long tick);

        void leave(int item, long tick);

        void move(int item, long segment, boolean forward, float progress, float speed, long tick);

        void speed(int item, float progress, float speed, long tick);
    }

    /** Builds one message. Reuse an instance: {@link #reset} keeps the buffer. */
    public static final class Writer {
        private byte[] buf = new byte[256];
        private int size;
        private int ops;
        private long baseTick;
        private int opsCountAt;

        public Writer() {
            reset(0L);
        }

        /** Starts a new message whose operations are timed relative to {@code baseTick}. */
        public void reset(long baseTick) {
            this.baseTick = baseTick;
            size = 0;
            ops = 0;
            putInt(MAGIC);
            putInt(VERSION);
            putVarLong(baseTick);
            opsCountAt = size;
            // op count: fixed 2 bytes (max 4096 fits in 13 bits), patched in toBytes()
            put(0);
            put(0);
        }

        public int opCount() {
            return ops;
        }

        /** True when another operation might not fit; send this message and {@link #reset}. */
        public boolean isFull() {
            return ops >= MAX_OPS || size > MAX_BYTES - 64;
        }

        public boolean enter(int item, long segment, boolean forward, float progress, float speed, long tick) {
            return write(OP_ENTER, item, segment, forward, progress, speed, tick, true, true);
        }

        public boolean leave(int item, long tick) {
            return write(OP_LEAVE, item, 0L, false, 0f, 0f, tick, false, false);
        }

        public boolean move(int item, long segment, boolean forward, float progress, float speed, long tick) {
            return write(OP_MOVE, item, segment, forward, progress, speed, tick, true, true);
        }

        public boolean speed(int item, float progress, float speed, long tick) {
            return write(OP_SPEED, item, 0L, false, progress, speed, tick, false, true);
        }

        private boolean write(int op, int item, long segment, boolean forward, float progress, float speed, long tick,
                              boolean hasSegment, boolean hasMotion) {
            long delta = tick - baseTick;
            if (isFull() || delta < 0 || delta > MAX_TICK_DELTA || item < 0 || segment < 0
                    || (hasMotion && !validMotion(progress, speed))) {
                return false;
            }
            put(op | (forward ? FORWARD_BIT : 0));
            putVarInt(item);
            if (hasSegment) {
                putVarLong(segment);
            }
            if (hasMotion) {
                putInt(Float.floatToRawIntBits(progress));
                putInt(Float.floatToRawIntBits(speed));
            }
            putVarInt((int) delta);
            ops++;
            return true;
        }

        /** The finished message. */
        public byte[] toBytes() {
            buf[opsCountAt] = (byte) (ops >>> 8);
            buf[opsCountAt + 1] = (byte) ops;
            return Arrays.copyOf(buf, size);
        }

        private void put(int b) {
            if (size == buf.length) {
                buf = Arrays.copyOf(buf, size << 1);
            }
            buf[size++] = (byte) b;
        }

        private void putInt(int v) {
            put(v >>> 24);
            put(v >>> 16);
            put(v >>> 8);
            put(v);
        }

        private void putVarInt(int v) {
            while ((v & ~0x7F) != 0) {
                put((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            put(v);
        }

        private void putVarLong(long v) {
            while ((v & ~0x7FL) != 0) {
                put((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            put((int) v);
        }
    }

    private static boolean validMotion(float progress, float speed) {
        return progress >= 0f && progress <= MAX_PROGRESS && speed >= 0f && speed <= MAX_SPEED;
    }

    /**
     * Validates {@code data[0..length)} and, only if it is entirely well formed, delivers its
     * operations to {@code listener}. Returns false (and delivers nothing) otherwise.
     */
    public static boolean read(byte[] data, int length, Listener listener) {
        if (length < 0 || length > data.length || length > MAX_BYTES) {
            return false;
        }
        Cursor c = new Cursor(data, length);
        return parse(c, null) && parse(new Cursor(data, length), listener);
    }

    private static final class Cursor {
        final byte[] data;
        final int end;
        int pos;
        boolean bad;

        Cursor(byte[] data, int end) {
            this.data = data;
            this.end = end;
        }

        int u8() {
            if (pos >= end) {
                bad = true;
                return 0;
            }
            return data[pos++] & 0xFF;
        }

        int int32() {
            return u8() << 24 | u8() << 16 | u8() << 8 | u8();
        }

        int varInt() {
            int result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                if (bad) {
                    return 0;
                }
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (shift == 28 && (b & 0x70) != 0) {
                        bad = true; // more than 32 bits
                    }
                    return result;
                }
            }
            bad = true;
            return 0;
        }

        long varLong() {
            long result = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                int b = u8();
                if (bad) {
                    return 0;
                }
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (shift == 63 && (b & 0x7E) != 0) {
                        bad = true;
                    }
                    return result;
                }
            }
            bad = true;
            return 0;
        }

        float f32() {
            return Float.intBitsToFloat(int32());
        }
    }

    /** One pass over the message; with a null listener it only validates. */
    private static boolean parse(Cursor c, Listener l) {
        if (c.int32() != MAGIC || c.int32() != VERSION || c.bad) {
            return false;
        }
        long base = c.varLong();
        int count = c.u8() << 8 | c.u8();
        if (c.bad || base < 0 || count > MAX_OPS) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int head = c.u8();
            int op = head & 0x7F;
            boolean forward = (head & FORWARD_BIT) != 0;
            int item = c.varInt();
            long segment = 0;
            float progress = 0;
            float speed = 0;
            boolean hasSegment = op == OP_ENTER || op == OP_MOVE;
            boolean hasMotion = op != OP_LEAVE;
            if (op < OP_ENTER || op > OP_SPEED) {
                return false;
            }
            if (hasSegment) {
                segment = c.varLong();
            }
            if (hasMotion) {
                progress = c.f32();
                speed = c.f32();
            }
            int delta = c.varInt();
            if (c.bad || item < 0 || segment < 0 || delta < 0 || delta > MAX_TICK_DELTA
                    || (hasMotion && !validMotion(progress, speed))) {
                return false;
            }
            if (l != null) {
                long tick = base + delta;
                switch (op) {
                    case OP_ENTER -> l.enter(item, segment, forward, progress, speed, tick);
                    case OP_LEAVE -> l.leave(item, tick);
                    case OP_MOVE -> l.move(item, segment, forward, progress, speed, tick);
                    default -> l.speed(item, progress, speed, tick);
                }
            }
        }
        return c.pos == c.end;
    }
}
