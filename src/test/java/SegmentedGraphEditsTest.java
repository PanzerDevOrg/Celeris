import net.bichal.mods.celeris.core.topology.ConnectionMask;
import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Random single-node edits (what a mod does when players place, break and rewire pipes) checked after every
 * step against a from-scratch statement of the fused network. The area is around the origin on purpose: it
 * contains the position (-1, -1, -1), whose packed form is all ones.
 */
@SuppressWarnings("unused")
class SegmentedGraphEditsTest {

    private static final int[] PLANE = {GraphTestSupport.NORTH, GraphTestSupport.SOUTH, GraphTestSupport.WEST, GraphTestSupport.EAST};

    private static int stepX(int d) {
        return d == GraphTestSupport.WEST ? -1 : d == GraphTestSupport.EAST ? 1 : 0;
    }

    private static int stepZ(int d) {
        return d == GraphTestSupport.NORTH ? -1 : d == GraphTestSupport.SOUTH ? 1 : 0;
    }

    @Test
    void placingBreakingAndRewiringMatchesTheFusedNetworkAfterEveryStep() {
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            SegmentedGraphImpl<String> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), () -> "");
            boolean[][] present = new boolean[9][9]; // x, z in -4..4, at y = -1
            Map<Long, Integer> masks = new HashMap<>();
            for (int step = 0; step < 150; step++) {
                int x = random.nextInt(9) - 4;
                int z = random.nextInt(9) - 4;
                boolean place = !present[x + 4][z + 4] || random.nextInt(4) != 0;
                present[x + 4][z + 4] = place;
                // like a mod: recompute this block and its neighbours from who is present
                java.util.List<int[]> touched = new java.util.ArrayList<>();
                touched.add(new int[]{x, z});
                for (int d : PLANE) touched.add(new int[]{x + stepX(d), z + stepZ(d)});
                if (!place) {
                    graph.removeNode(NodeId.pack(x, -1, z));
                    masks.remove(NodeId.packRaw(x, -1, z));
                }
                for (int[] c : touched) {
                    if (c[0] < -4 || c[0] > 4 || c[1] < -4 || c[1] > 4 || !present[c[0] + 4][c[1] + 4]) continue;
                    int bits = 0;
                    for (int d : PLANE) {
                        int nx = c[0] + stepX(d), nz = c[1] + stepZ(d);
                        if (nx >= -4 && nx <= 4 && nz >= -4 && nz <= 4 && present[nx + 4][nz + 4]) bits |= 1 << d;
                    }
                    long raw = NodeId.packRaw(c[0], -1, c[1]);
                    Integer old = masks.put(raw, bits);
                    if (old == null || old != bits) {
                        graph.updateConnections(NodeId.pack(c[0], -1, c[1]), ConnectionMask.of(bits));
                    }
                }
                assertEquals(SegmentedGraphBulkTest.oracle(masks), SegmentedGraphBulkTest.signatures(graph),
                        "seed " + seed + " step " + step);
            }
        }
    }
}
