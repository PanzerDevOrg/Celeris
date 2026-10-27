import net.bichal.mods.celeris_example.pipe.PipeSyncMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipeSyncMessageTest {

    private static PipeSyncMessage sample() {
        return new PipeSyncMessage(true, 12345L,
                new int[]{0, 7}, new int[]{3, 99}, new int[]{4},
                new long[]{10L, 11L}, new long[][]{{1L, 2L, 3L}, {-1L, 5L}}, new long[]{9L});
    }

    @Test
    void roundTripKeepsEverything() {
        PipeSyncMessage read = PipeSyncMessage.read(sample().toBytes());
        assertNotNull(read);
        assertTrue(read.reset());
        assertEquals(12345L, read.serverTick());
        assertArrayEquals(new int[]{0, 7}, read.defHandles());
        assertArrayEquals(new int[]{3, 99}, read.defItems());
        assertArrayEquals(new int[]{4}, read.released());
        assertArrayEquals(new long[]{10L, 11L}, read.segmentIds());
        assertArrayEquals(new long[]{-1L, 5L}, read.segmentPositions()[1]);
        assertArrayEquals(new long[]{9L}, read.removedSegments());
    }

    @Test
    void malformedMessagesAreRejected() {
        byte[] good = sample().toBytes();
        assertNull(PipeSyncMessage.read(null));
        assertNull(PipeSyncMessage.read(new byte[0]));
        for (int cut = 0; cut < good.length; cut++) {
            assertNull(PipeSyncMessage.read(java.util.Arrays.copyOf(good, cut)), "truncated at " + cut);
        }
        byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
        assertNull(PipeSyncMessage.read(trailing));
        byte[] hugeCount = good.clone();
        hugeCount[9] = 0x7F; // first list count: far more entries than bytes
        assertNull(PipeSyncMessage.read(hugeCount));
        assertNull(PipeSyncMessage.read(new byte[PipeSyncMessage.MAX_BYTES + 1]));
    }

    @Test
    void emptyMessageIsEmptyUnlessItResets() {
        PipeSyncMessage none = new PipeSyncMessage(false, 1L, new int[0], new int[0], new int[0],
                new long[0], new long[0][], new long[0]);
        assertTrue(none.isEmpty());
        assertFalse(sample().isEmpty());
        assertNotNull(PipeSyncMessage.read(none.toBytes()));
    }
}
