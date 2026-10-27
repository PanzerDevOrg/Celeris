import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class SegmentedGraphBulkTest {

    private static final int[] PLANE_DIRS = {GraphTestSupport.NORTH, GraphTestSupport.SOUTH, GraphTestSupport.WEST, GraphTestSupport.EAST};

    /** A random pipe layout on a grid: cells present, with each cell connected to every present neighbour. */
    private static Map<Long, Integer> randomLayout(long seed, int size, double density) {
        Random random = new Random(seed);
        Map<Long, Integer> masks = new HashMap<>();
        boolean[][] present = new boolean[size][size];
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                present[x][z] = random.nextDouble() < density;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (!present[x][z]) continue;
                int bits = 0;
                for (int d : PLANE_DIRS) {
                    int nx = x + stepX(d), nz = z + stepZ(d);
                    if (nx >= 0 && nz >= 0 && nx < size && nz < size && present[nx][nz]) bits |= 1 << d;
                }
                masks.put(NodeId.packRaw(x, 0, z), bits);
            }
        }
        return masks;
    }

    private static int stepX(int d) {
        return d == GraphTestSupport.WEST ? -1 : d == GraphTestSupport.EAST ? 1 : 0;
    }

    private static int stepZ(int d) {
        return d == GraphTestSupport.NORTH ? -1 : d == GraphTestSupport.SOUTH ? 1 : 0;
    }

    /** Independent statement of what the fused graph must contain, straight from the masks. */
    static TreeSet<String> oracle(Map<Long, Integer> masks) {
        TreeSet<String> result = new TreeSet<>();
        for (var entry : masks.entrySet()) {
            int bits = entry.getValue();
            if (isStraight(bits)) continue;
            for (int d = 0; d < 6; d++) {
                if ((bits & (1 << d)) == 0) continue;
                List<Long> path = new ArrayList<>();
                path.add(entry.getKey());
                long current = entry.getKey();
                int dir = d;
                while (true) {
                    long next = NodeId.stepRaw(current, dir);
                    path.add(next);
                    Integer nb = masks.get(next);
                    int incoming = GraphTestSupport.opposite(dir);
                    if (nb == null || (nb & (1 << incoming)) == 0) break;
                    if (!isStraight(nb)) {
                        result.add(signature(path));
                        break;
                    }
                    dir = Integer.numberOfTrailingZeros(nb & ~(1 << incoming));
                    current = next;
                }
            }
        }
        return result;
    }

    private static boolean isStraight(int bits) {
        return Integer.bitCount(bits) == 2 && opposite(bits);
    }

    private static boolean opposite(int bits) {
        int a = Integer.numberOfTrailingZeros(bits);
        int b = 31 - Integer.numberOfLeadingZeros(bits);
        return GraphTestSupport.opposite(a) == b;
    }

    private static String signature(List<Long> path) {
        List<Long> reversed = new ArrayList<>(path);
        Collections.reverse(reversed);
        String a = path.toString();
        String b = reversed.toString();
        return a.compareTo(b) <= 0 ? a : b;
    }

    static TreeSet<String> signatures(SegmentedGraphImpl<?> graph) {
        TreeSet<String> result = new TreeSet<>();
        for (GraphSegment<?> s : graph.segments()) {
            List<Long> path = new ArrayList<>();
            for (int i = 0; i < s.positionCount(); i++) path.add(s.rawPositionAt(i));
            assertTrue(result.add(signature(path)), "duplicate segment");
        }
        return result;
    }

    private static SegmentedGraphImpl<String> newGraph() {
        return new SegmentedGraphImpl<>(new NetworkTopologyImpl(), () -> "");
    }

    @Test
    void oneBatchBuildsExactlyTheFusedNetwork() {
        for (long seed = 1; seed <= 25; seed++) {
            Map<Long, Integer> layout = randomLayout(seed, 24, 0.55);
            List<Long> order = new ArrayList<>(layout.keySet());
            Collections.shuffle(order, new Random(seed));
            long[] ids = new long[order.size()];
            int[] bits = new int[order.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = order.get(i);
                bits[i] = layout.get(ids[i]);
            }
            SegmentedGraphImpl<String> graph = newGraph();
            graph.addNodes(ids, bits, ids.length);
            assertEquals(oracle(layout), signatures(graph), "seed " + seed);
        }
    }

    @Test
    void successiveBatchesIncludingUpdatesOfExistingNodesStayCorrect() {
        for (long seed = 100; seed < 115; seed++) {
            Map<Long, Integer> full = randomLayout(seed, 20, 0.6);
            List<Long> order = new ArrayList<>(full.keySet());
            Collections.shuffle(order, new Random(seed));
            SegmentedGraphImpl<String> graph = newGraph();
            Map<Long, Integer> current = new HashMap<>();
            int chunk = Math.max(1, order.size() / 5);
            for (int from = 0; from < order.size(); from += chunk) {
                List<Long> batch = new ArrayList<>(order.subList(from, Math.min(order.size(), from + chunk)));
                for (Long id : batch) current.put(id, 0);
                // every node whose neighbourhood changed is part of the batch with its new mask
                Map<Long, Integer> next = new HashMap<>();
                for (Long id : current.keySet()) {
                    int bits = 0;
                    for (int d : PLANE_DIRS) {
                        Long n = NodeId.stepRaw(id, d);
                        if (current.containsKey(n) && (full.get(id) & (1 << d)) != 0) bits |= 1 << d;
                    }
                    next.put(id, bits);
                }
                List<Long> touched = new ArrayList<>();
                for (var e : next.entrySet()) {
                    if (!e.getValue().equals(current.get(e.getKey())) || batch.contains(e.getKey())) touched.add(e.getKey());
                }
                long[] ids = new long[touched.size()];
                int[] bits = new int[touched.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = touched.get(i);
                    bits[i] = next.get(ids[i]);
                }
                graph.addNodes(ids, bits, ids.length);
                current = next;
                assertEquals(oracle(current), signatures(graph), "seed " + seed + " after " + (from + chunk) + " nodes");
            }
        }
    }

    @Test
    void aLongStraightRunBecomesOneSegment() {
        int n = 5000;
        long[] ids = new long[n];
        int[] bits = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = NodeId.packRaw(i, 0, 0);
            bits[i] = i == 0 ? 1 << GraphTestSupport.EAST : i == n - 1 ? 1 << GraphTestSupport.WEST
                    : (1 << GraphTestSupport.EAST) | (1 << GraphTestSupport.WEST);
        }
        SegmentedGraphImpl<String> graph = newGraph();
        graph.addNodes(ids, bits, n);
        assertEquals(1, graph.segments().size());
        assertEquals(n - 1, graph.segments().iterator().next().length());
    }

    @Test
    void aBatchInsideAnExistingRunSplitsItAndKeepsUntouchedSegments() {
        SegmentedGraphImpl<String> graph = newGraph();
        long[] ids = new long[11];
        int[] bits = new int[11];
        for (int i = 0; i < 11; i++) {
            ids[i] = NodeId.packRaw(i, 0, 0);
            bits[i] = i == 0 ? 1 << GraphTestSupport.EAST : i == 10 ? 1 << GraphTestSupport.WEST
                    : (1 << GraphTestSupport.EAST) | (1 << GraphTestSupport.WEST);
        }
        graph.addNodes(ids, bits, 11);
        assertEquals(1, graph.segments().size());

        graph.addNodes(new long[]{NodeId.packRaw(5, 0, 0)},
                new int[]{(1 << GraphTestSupport.EAST) | (1 << GraphTestSupport.WEST) | (1 << GraphTestSupport.SOUTH)}, 1);
        assertEquals(2, graph.segments().size());
        for (GraphSegment<String> s : graph.segments()) assertEquals(5, s.length());
    }

    @Test
    void trackingGraphsKeepUnchangedSegmentsAcrossABatch() {
        var graph = new SegmentedGraphImpl<String>(new NetworkTopologyImpl(), () -> "", (removed, created) -> {
        });
        Map<Long, Integer> layout = randomLayout(7, 16, 0.6);
        long[] ids = layout.keySet().stream().mapToLong(Long::longValue).toArray();
        int[] bits = new int[ids.length];
        for (int i = 0; i < ids.length; i++) bits[i] = layout.get(ids[i]);
        graph.addNodes(ids, bits, ids.length);
        Map<Long, GraphSegment<String>> before = new HashMap<>();
        for (GraphSegment<String> s : graph.segments()) before.put(s.id(), s);

        graph.addNodes(ids, bits, ids.length); // same data again: nothing changes
        assertEquals(before.size(), graph.segments().size());
        for (GraphSegment<String> s : graph.segments()) assertSame(before.get(s.id()), s);
    }
}
