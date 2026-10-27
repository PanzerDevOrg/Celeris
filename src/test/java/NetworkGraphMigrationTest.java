import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.PayloadMigrator;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphMigrationTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int N = GraphTestSupport.NORTH;
    private static final int S = GraphTestSupport.SOUTH;

    /** Items on a segment, each at a position index along it. Item ids are globally unique. */
    static final class Belt {
        final List<int[]> items = new ArrayList<>(); // {itemId, positionIndex}
    }

    /** Re-homes items by world position; items with nowhere to go wait in {@link #orphans} or are spilled. */
    static final class ItemMigrator implements PayloadMigrator<Belt> {
        final List<long[]> orphans = new ArrayList<>(); // {itemId, rawPosition}
        int calls;

        @Override
        public void migrate(List<GraphSegment<Belt>> removed, List<GraphSegment<Belt>> created) {
            calls++;
            for (GraphSegment<Belt> old : removed) {
                for (int[] item : old.payload().items) {
                    orphans.add(new long[]{item[0], old.rawPositionAt(item[1])});
                }
                old.payload().items.clear();
            }
            for (int i = orphans.size() - 1; i >= 0; i--) {
                long[] orphan = orphans.get(i);
                if (adopt(created, orphan)) {
                    orphans.remove(i);
                }
            }
        }

        private static boolean adopt(List<GraphSegment<Belt>> created, long[] orphan) {
            GraphSegment<Belt> endpointMatch = null;
            int endpointIndex = -1;
            for (GraphSegment<Belt> segment : created) {
                for (int p = 0; p < segment.positionCount(); p++) {
                    if (segment.rawPositionAt(p) != orphan[1]) continue;
                    if (p > 0 && p < segment.positionCount() - 1) {
                        segment.payload().items.add(new int[]{(int) orphan[0], p});
                        return true;
                    }
                    if (endpointMatch == null) {
                        endpointMatch = segment;
                        endpointIndex = p;
                    }
                }
            }
            if (endpointMatch == null) return false;
            endpointMatch.payload().items.add(new int[]{(int) orphan[0], endpointIndex});
            return true;
        }
    }

    private final ItemMigrator migrator = new ItemMigrator();
    private final SegmentedGraphImpl<Belt> graph =
            new SegmentedGraphImpl<>(new NetworkTopologyImpl(), Belt::new, migrator);

    private void line(NodeId start, int dir, int count) {
        int back = GraphTestSupport.opposite(dir);
        for (int i = 0; i < count; i++) {
            NodeId pos = GraphTestSupport.relative(start, dir, i);
            graph.addNode(pos, i == 0 ? GraphTestSupport.dirs(dir)
                    : i == count - 1 ? GraphTestSupport.dirs(back) : GraphTestSupport.dirs(dir, back));
        }
    }

    /** Puts one item on every position of every segment, returns how many were placed. */
    private int fillWithItems(int firstId) {
        int id = firstId;
        for (GraphSegment<Belt> segment : graph.segments()) {
            for (int p = 0; p < segment.positionCount(); p++) {
                segment.payload().items.add(new int[]{id++, p});
            }
        }
        return id - firstId;
    }

    private Set<Integer> liveItems() {
        Set<Integer> seen = new HashSet<>();
        for (GraphSegment<Belt> segment : graph.segments()) {
            for (int[] item : segment.payload().items) {
                assertTrue(seen.add(item[0]), "item " + item[0] + " duplicated");
            }
        }
        for (long[] orphan : migrator.orphans) {
            assertTrue(seen.add((int) orphan[0]), "item " + orphan[0] + " duplicated");
        }
        return seen;
    }

    private static Set<Integer> range(int from, int count) {
        Set<Integer> r = new HashSet<>();
        for (int i = 0; i < count; i++) r.add(from + i);
        return r;
    }

    @Test
    void splittingALineInTheMiddleKeepsEveryItem() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        int placed = fillWithItems(0);
        assertEquals(11, placed);

        NodeId mid = GraphTestSupport.relative(start, E, 5);
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));

        assertEquals(2, graph.segments().size());
        assertEquals(range(0, placed), liveItems());
        assertTrue(migrator.orphans.isEmpty());
    }

    @Test
    void mergingTwoLinesKeepsEveryItem() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        NodeId mid = GraphTestSupport.relative(start, E, 5);
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
        assertEquals(2, graph.segments().size());
        int placed = fillWithItems(100);

        graph.updateConnections(mid, GraphTestSupport.dirs(E, W));

        assertEquals(1, graph.segments().size());
        assertEquals(range(100, placed), liveItems());
        // the shared junction position was on both old segments; it is now one position
        assertEquals(11, graph.segments().iterator().next().positionCount());
    }

    @Test
    void removingAnEndKeepsItemsExceptThoseOnTheRemovedBlock() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        int placed = fillWithItems(0);
        NodeId last = GraphTestSupport.relative(start, E, 10);
        NodeId beforeLast = GraphTestSupport.relative(start, E, 9);

        graph.removeNode(last);
        graph.updateConnections(beforeLast, GraphTestSupport.dirs(W));

        Set<Integer> expected = range(0, placed);
        Set<Integer> live = liveItems();
        // the item that sat on the removed block waits as an orphan for the consumer to spill
        assertEquals(1, migrator.orphans.size());
        assertEquals(last.raw(), migrator.orphans.get(0)[1]);
        assertEquals(expected, live);
        assertEquals(1, graph.segments().size());
    }

    @Test
    void turningAStraightRunIntoACrossingKeepsEveryItem() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        int placed = fillWithItems(0);
        NodeId mid = GraphTestSupport.relative(start, E, 5);
        NodeId northEnd = GraphTestSupport.relative(mid, N, 4);
        line(northEnd, S, 4); // ends one block short of mid, facing it
        NodeId southEnd = GraphTestSupport.relative(mid, S, 4);
        line(southEnd, N, 4);

        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, N, S));
        // close the gap blocks so the crossing is fully wired
        graph.updateConnections(GraphTestSupport.relative(mid, N, 1), GraphTestSupport.dirs(N, S));
        graph.updateConnections(GraphTestSupport.relative(mid, S, 1), GraphTestSupport.dirs(N, S));

        Set<Integer> live = liveItems();
        assertTrue(live.containsAll(range(0, placed)), "main line items lost: " + live);
        assertEquals(live.size(), new HashSet<>(live).size());
    }

    @Test
    void unchangedSegmentsKeepIdAndPayloadWhenANeighbourIsRebuilt() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        NodeId mid = GraphTestSupport.relative(start, E, 5);
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
        fillWithItems(0);
        GraphSegment<Belt> first = graph.segmentAt(GraphTestSupport.relative(start, E, 2));
        long firstId = first.id();
        Belt firstPayload = first.payload();

        // changing the junction's third branch rebuilds both segments touching it, to the same shape
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, N));

        GraphSegment<Belt> after = graph.segmentAt(GraphTestSupport.relative(start, E, 2));
        assertSame(first, after);
        assertEquals(firstId, after.id());
        assertSame(firstPayload, after.payload());
        assertEquals(2, graph.segments().size());
    }

    @Test
    void idsAreMonotonicAndNeverReused() {
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(start, E, 11);
        NodeId mid = GraphTestSupport.relative(start, E, 5);
        long previousMax = graph.segments().iterator().next().id();
        Set<Long> seen = new HashSet<>();
        seen.add(previousMax);
        for (int round = 0; round < 4; round++) {
            graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
            for (GraphSegment<Belt> segment : graph.segments()) {
                if (seen.add(segment.id())) {
                    assertTrue(segment.id() > previousMax, "new ids only grow");
                }
            }
            previousMax = seen.stream().mapToLong(Long::longValue).max().orElseThrow();
            graph.updateConnections(mid, GraphTestSupport.dirs(E, W));
            for (GraphSegment<Belt> segment : graph.segments()) {
                if (seen.add(segment.id())) {
                    assertTrue(segment.id() > previousMax);
                }
            }
            previousMax = seen.stream().mapToLong(Long::longValue).max().orElseThrow();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void migratorSeesTheGraphAlreadyConsistentAndCannotMutateIt() {
        List<Integer> segmentCounts = new ArrayList<>();
        SegmentedGraphImpl<Belt>[] holder = new SegmentedGraphImpl[1];
        holder[0] = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), Belt::new, (removed, created) -> {
            segmentCounts.add(holder[0].segments().size());
            assertThrows(IllegalStateException.class,
                    () -> holder[0].addNode(GraphTestSupport.pos(99, 0, 99), GraphTestSupport.dirs()));
        });
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, E);
        holder[0].addNode(a, GraphTestSupport.dirs(E));
        holder[0].addNode(b, GraphTestSupport.dirs(W));

        assertEquals(List.of(1), segmentCounts);
        assertNotEquals(0, holder[0].segments().size());
    }

    @Test
    void withoutAMigratorTheLegacyBehaviourIsUnchanged() {
        SegmentedGraphImpl<Belt> legacy = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), Belt::new);
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        legacy.addNode(a, GraphTestSupport.dirs(E));
        legacy.addNode(GraphTestSupport.relative(a, E), GraphTestSupport.dirs(W));
        assertEquals(1, legacy.segments().size());
        assertTrue(legacy.segments().iterator().next().id() >= 0);
    }
}
