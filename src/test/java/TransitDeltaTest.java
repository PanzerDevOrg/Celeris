import net.bichal.mods.celeris.api.transit.ClientItemTable;
import net.bichal.mods.celeris.api.transit.TransitDelta;
import net.bichal.mods.celeris.api.transit.TransitMotion;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.network.CompressedPayload;
import net.bichal.mods.celeris.network.PayloadCompression;
import net.bichal.mods.celeris.network.TransitDeltaPackets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class TransitDeltaTest {

    /** Records the operations as text. */
    private static final class Log implements TransitDelta.Listener {
        final List<String> ops = new ArrayList<>();

        public void enter(int i, long s, boolean f, float p, float v, long t) {
            ops.add("enter " + i + " " + s + " " + f + " " + p + " " + v + " " + t);
        }

        public void leave(int i, long t) {
            ops.add("leave " + i + " " + t);
        }

        public void move(int i, long s, boolean f, float p, float v, long t) {
            ops.add("move " + i + " " + s + " " + f + " " + p + " " + v + " " + t);
        }

        public void speed(int i, float p, float v, long t) {
            ops.add("speed " + i + " " + p + " " + v + " " + t);
        }
    }

    private static byte[] sample() {
        TransitDelta.Writer w = new TransitDelta.Writer();
        w.reset(1_000_000_000_000L);
        assertTrue(w.enter(7, 3L, true, 0.25f, 0.1f, 1_000_000_000_000L));
        assertTrue(w.enter(Integer.MAX_VALUE, Long.MAX_VALUE, false, 12.5f, 0.4f, 1_000_000_000_000L + 65535));
        assertTrue(w.speed(7, 3.5f, 0f, 1_000_000_000_007L));
        assertTrue(w.move(7, 1L << 40, false, 0f, 0.1f, 1_000_000_000_009L));
        assertTrue(w.leave(Integer.MAX_VALUE, 1_000_000_000_010L));
        return w.toBytes();
    }

    @Test
    void roundTripKeepsEveryOperationExactly() {
        byte[] bytes = sample();
        Log log = new Log();
        assertTrue(TransitDelta.read(bytes, bytes.length, log));
        assertEquals(List.of(
                "enter 7 3 true 0.25 0.1 1000000000000",
                "enter 2147483647 9223372036854775807 false 12.5 0.4 1000000000000".replace("1000000000000", "1000000065535"),
                "speed 7 3.5 0.0 1000000000007",
                "move 7 1099511627776 false 0.0 0.1 1000000000009",
                "leave 2147483647 1000000000010"), log.ops);
    }

    @Test
    void writerRefusesWhatTheReaderWouldRefuse() {
        TransitDelta.Writer w = new TransitDelta.Writer();
        w.reset(100);
        assertFalse(w.enter(1, 1, true, Float.NaN, 0.1f, 100));
        assertFalse(w.enter(1, 1, true, 0f, Float.POSITIVE_INFINITY, 100));
        assertFalse(w.enter(1, 1, true, -1f, 0.1f, 100));
        assertFalse(w.enter(1, 1, true, 0f, 1000f, 100));
        assertFalse(w.enter(-1, 1, true, 0f, 0.1f, 100));
        assertFalse(w.enter(1, -1, true, 0f, 0.1f, 100));
        assertFalse(w.leave(1, 99), "before the base tick");
        assertFalse(w.leave(1, 100 + 65536), "too far after the base tick");
        assertEquals(0, w.opCount());
        assertTrue(w.leave(1, 100));
    }

    @Test
    void aFullMessageSaysSoAndNothingIsLost() {
        TransitDelta.Writer w = new TransitDelta.Writer();
        w.reset(0);
        int written = 0;
        while (!w.isFull()) {
            assertTrue(w.enter(written, written, true, 1f, 0.1f, 0));
            written++;
        }
        assertEquals(TransitDelta.MAX_OPS, written);
        assertFalse(w.leave(1, 0));
        byte[] bytes = w.toBytes();
        assertTrue(bytes.length <= TransitDelta.MAX_BYTES);
        Log log = new Log();
        assertTrue(TransitDelta.read(bytes, bytes.length, log));
        assertEquals(written, log.ops.size());
    }

    @Test
    void malformedMessagesAreDroppedWholeAndNeverHalfApplied() {
        byte[] good = sample();
        // every truncation
        for (int cut = 0; cut < good.length; cut++) {
            Log log = new Log();
            assertFalse(TransitDelta.read(good, cut, log), "cut " + cut);
            assertTrue(log.ops.isEmpty());
        }
        // trailing bytes
        byte[] longer = java.util.Arrays.copyOf(good, good.length + 1);
        assertFalse(TransitDelta.read(longer, longer.length, new Log()));
        // random corruption: never throws; when it still parses, the listener got exactly what a clean parse gives
        Random random = new Random(5);
        for (int round = 0; round < 20_000; round++) {
            byte[] copy = good.clone();
            for (int k = 0; k < 1 + random.nextInt(4); k++) copy[random.nextInt(copy.length)] = (byte) random.nextInt();
            Log log = new Log();
            boolean ok = TransitDelta.read(copy, copy.length, log);
            if (!ok) assertTrue(log.ops.isEmpty());
        }
        // pure noise
        for (int round = 0; round < 5_000; round++) {
            byte[] noise = new byte[random.nextInt(200)];
            random.nextBytes(noise);
            assertFalse(TransitDelta.read(noise, noise.length, new Log()));
        }
    }

    @Test
    void outOfRangeValuesInTheBytesAreRejected() {
        // NaN progress forged by patching the bytes of a valid enter
        TransitDelta.Writer w = new TransitDelta.Writer();
        w.reset(0);
        w.enter(1, 1, true, 1f, 0.1f, 0);
        byte[] bytes = w.toBytes();
        int nan = Float.floatToRawIntBits(Float.NaN);
        // header 8 + baseTick varlong 1 + count 2 + opcode 1 + item 1 + segment 1 = progress at 14
        for (int i = 0; i < 4; i++) bytes[14 + i] = (byte) (nan >>> (24 - 8 * i));
        assertFalse(TransitDelta.read(bytes, bytes.length, new Log()));

        byte[] wrongVersion = sample();
        wrongVersion[7] = 9;
        assertFalse(TransitDelta.read(wrongVersion, wrongVersion.length, new Log()));
        byte[] tooManyOps = sample();
        tooManyOps[8 + 6] = (byte) 0xFF; // count high bytes inside the header area: also invalid
        assertFalse(TransitDelta.read(tooManyOps, tooManyOps.length, new Log()));
    }

    @Test
    void packetsRoundTripThroughCompressionAndRefuseHostilePayloads() {
        TransitDelta.Writer w = new TransitDelta.Writer();
        w.reset(0);
        for (int i = 0; i < 2000; i++) w.enter(i, i / 10, i % 2 == 0, i * 0.01f, 0.1f, 0);
        byte[] message = w.toBytes();
        CompressedPayload payload = TransitDeltaPackets.encode(message);
        assertTrue(payload.compressed());
        assertTrue(payload.data().length < message.length / 2, "ratio " + payload.data().length + "/" + message.length);
        Log log = new Log();
        assertTrue(TransitDeltaPackets.decode(payload, log));
        assertEquals(2000, log.ops.size());

        // declares more than a delta may be
        assertFalse(TransitDeltaPackets.decode(new CompressedPayload(payload.data(), true, TransitDelta.MAX_BYTES + 1), new Log()));
        assertFalse(TransitDeltaPackets.decode(new CompressedPayload(payload.data(), true, -1), new Log()));
        // lies about its size
        assertFalse(TransitDeltaPackets.decode(new CompressedPayload(payload.data(), true, message.length - 1), new Log()));
        // garbage
        assertFalse(TransitDeltaPackets.decode(new CompressedPayload(new byte[]{1, 2, 3, 4}, true, 100), new Log()));
        // valid compression of something that is not a delta
        assertFalse(TransitDeltaPackets.decode(PayloadCompression.compress(new byte[1000]), new Log()));
        // oversized compressed blob is not even inflated
        assertFalse(TransitDeltaPackets.decode(new CompressedPayload(new byte[TransitDeltaPackets.MAX_COMPRESSED_BYTES + 1], true, 10), new Log()));
        assertFalse(TransitDeltaPackets.decode(null, new Log()));
    }

    @Test
    void clientTableFollowsEnterMoveSpeedLeaveAndIsBounded() {
        ClientItemTable table = new ClientItemTable();
        table.enter(1, 10, true, 0f, 0.5f, 100);
        table.enter(2, 10, true, 1f, 0.5f, 100);
        assertEquals(2, table.size());
        int slot = table.slotOf(1);
        assertEquals(5f, table.progressAt(slot, 110, 100f), 1e-6f);
        table.speed(1, 5f, 0f, 110);
        assertEquals(5f, table.progressAt(slot, 200, 100f), 1e-6f, "stopped");
        table.move(1, 20, false, 0f, 0.25f, 120);
        assertEquals(20L, table.segmentAt(slot));
        assertFalse(table.forwardAt(slot));
        table.leave(2, 130);
        table.leave(99, 130); // unknown item: ignored
        assertEquals(1, table.size());
        assertFalse(table.isLive(table.slotOf(2)));
        table.enter(3, 1, true, 0f, 0.1f, 140); // reuses the freed slot
        assertEquals(2, table.size());
        assertTrue(table.slotLimit() <= 2);
        table.speed(77, 0f, 0.1f, 1); // unknown: ignored
        assertEquals(2, table.size());
    }

    @Test
    void clearedClientTableIsEmptyAndUsableAgain() {
        ClientItemTable table = new ClientItemTable();
        table.enter(1, 10, true, 0f, 0.5f, 100);
        table.enter(2, 10, true, 1f, 0.5f, 100);
        table.leave(1, 105);
        table.clear();
        assertEquals(0, table.size());
        assertEquals(0, table.slotLimit());
        assertEquals(-1, table.slotOf(2));
        table.enter(2, 11, false, 2f, 0.25f, 200);
        assertEquals(1, table.size());
        assertEquals(11L, table.segmentAt(table.slotOf(2)));
        assertFalse(table.isLive(5));
    }

    @Test
    void motionMatchesTheServerAdditionAndInterpolatesAlongTheBlocks() {
        // the server adds speed once per tick
        float server = 0.3f;
        float speed = 0.1f;
        for (int i = 0; i < 1000; i++) server += speed;
        assertEquals(server, TransitMotion.progress(0.3f, speed, 0L, 1000, 1000f), 1e-3f);
        assertEquals(0.3f, TransitMotion.progress(0.3f, speed, 50L, 10, 5f), "before entry: where it started");
        assertEquals(5f, TransitMotion.progress(0.3f, speed, 0L, 1_000_000, 5f), "clamped at the exit");
        assertEquals(0f, TransitMotion.progress(0f, Float.NaN, 0L, 10, 5f));

        long[] path = {NodeId.packRaw(0, 0, 0), NodeId.packRaw(1, 0, 0), NodeId.packRaw(1, 0, 1)};
        float[] out = new float[3];
        TransitMotion.position(path, 3, true, 0f, out);
        assertArrayEquals(new float[]{0.5f, 0.5f, 0.5f}, out, 1e-6f);
        TransitMotion.position(path, 3, true, 1.5f, out);
        assertArrayEquals(new float[]{1.5f, 0.5f, 1f}, out, 1e-6f);
        TransitMotion.position(path, 3, false, 0.5f, out); // from the B end
        assertArrayEquals(new float[]{1.5f, 0.5f, 1f}, out, 1e-6f);
        TransitMotion.position(path, 3, true, 99f, out);
        assertArrayEquals(new float[]{1.5f, 0.5f, 1.5f}, out, 1e-6f);

        assertEquals(4f, TransitMotion.limitBehind(9f, 4.25f, 0.25f));
        assertEquals(0f, TransitMotion.limitBehind(9f, 0.1f, 0.25f));
        assertEquals(1f, TransitMotion.limitBehind(1f, 4.25f, 0.25f));
    }
}
