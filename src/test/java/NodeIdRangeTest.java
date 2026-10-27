import net.bichal.mods.celeris.core.topology.NodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdRangeTest {

    @Test
    void extremesRoundTrip() {
        int[] xz = {NodeId.MIN_XZ, -1, 0, 1, NodeId.MAX_XZ};
        int[] ys = {NodeId.MIN_Y, -64, -1, 0, 319, NodeId.MAX_Y};
        for (int x : xz) {
            for (int y : ys) {
                for (int z : xz) {
                    assertTrue(NodeId.inRange(x, y, z));
                    NodeId id = NodeId.packChecked(x, y, z);
                    assertEquals(x, id.x());
                    assertEquals(y, id.y());
                    assertEquals(z, id.z());
                }
            }
        }
    }

    @Test
    void outOfRangeIsRejectedByCheckedAndWrapsInUnchecked() {
        assertFalse(NodeId.inRange(NodeId.MAX_XZ + 1, 0, 0));
        assertFalse(NodeId.inRange(0, NodeId.MIN_Y - 1, 0));
        assertFalse(NodeId.inRange(0, 0, NodeId.MIN_XZ - 1));
        assertThrows(IllegalArgumentException.class, () -> NodeId.packChecked(0, NodeId.MAX_Y + 1, 0));
        assertEquals(NodeId.pack(NodeId.MIN_XZ, 0, 0), NodeId.pack(NodeId.MAX_XZ + 1, 0, 0));
    }

    @Test
    void vanillaDimensionLimitsFit() {
        assertTrue(NodeId.inRange(30_000_000, 2031, -30_000_000));
        assertTrue(NodeId.inRange(-30_000_000, -2032, 30_000_000));
    }
}
