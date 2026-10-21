import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import com.panzer.mods.celeris.pipeline.transit.FilterRouter;
import com.panzer.mods.celeris.pipeline.transit.JunctionRouter;
import com.panzer.mods.celeris.pipeline.transit.PriorityRouter;
import com.panzer.mods.celeris.pipeline.transit.RoundRobinRouter;
import com.panzer.mods.celeris.pipeline.transit.TransitConfig;
import com.panzer.mods.celeris.pipeline.transit.TransitEndpoints;
import com.panzer.mods.celeris.pipeline.transit.TransitMigrator;
import com.panzer.mods.celeris.pipeline.transit.TransitPayload;
import com.panzer.mods.celeris.pipeline.transit.TransitQueue;
import com.panzer.mods.celeris.pipeline.transit.TransitSolver;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class TransitSolverTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int N = GraphTestSupport.NORTH;
    private static final int S = GraphTestSupport.SOUTH;

    /** Records deliveries as "tick:node:handle"; can reject the first N offers. */
    static class Sink implements TransitEndpoints {
        final Map<Long, Integer> terminals = new HashMap<>(); // node raw -> terminal direction
        final List<String> deliveries = new ArrayList<>();
        final Map<Long, List<Integer>> byNode = new HashMap<>();
        int rejectFirst;
        int offers;
        TransitSolver solver;

        Sink terminal(NodeId node, int direction) {
            terminals.put(node.raw(), direction);
            return this;
        }

        @Override
        public int terminalMask(long nodeRaw) {
            Integer d = terminals.get(nodeRaw);
            return d == null ? 0 : 1 << d;
        }

        @Override
        public boolean deliver(long nodeRaw, int direction, int handle) {
            if (offers++ < rejectFirst) {
                return false;
            }
            deliveries.add(solver.tickCount() + ":" + nodeRaw + ":" + handle);
            byNode.computeIfAbsent(nodeRaw, k -> new ArrayList<>()).add(handle);
            return true;
        }
    }

    static final class Net {
        final SegmentedGraphImpl<TransitPayload> graph;
        final TransitSolver solver;
        final Sink sink;
        final List<Integer> lost = new ArrayList<>();

        Net(Sink sink, JunctionRouter router, TransitConfig config, TransitSolver.ProgressAdvance advance, boolean migrate) {
            this.sink = sink;
            TransitMigrator migrator = migrate ? new TransitMigrator((h, s, n) -> lost.add(h)) : null;
            graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new, migrator);
            solver = advance == null
                    ? new TransitSolver(config, router, sink, migrator)
                    : new TransitSolver(config, router, sink, migrator, advance);
            solver.bind(graph);
            sink.solver = solver;
        }

        Net(Sink sink, JunctionRouter router) {
            this(sink, router, new TransitConfig(0.25f, 0.5f), null, false);
        }

        void line(NodeId start, int dir, int count) {
            int back = GraphTestSupport.opposite(dir);
            for (int i = 0; i < count; i++) {
                NodeId pos = GraphTestSupport.relative(start, dir, i);
                graph.addNode(pos, i == 0 ? GraphTestSupport.dirs(dir)
                        : i == count - 1 ? GraphTestSupport.dirs(back) : GraphTestSupport.dirs(dir, back));
            }
        }

        void tick(int n) {
            for (int i = 0; i < n; i++) solver.tick();
        }
    }

    private static final NodeId ORIGIN = GraphTestSupport.pos(0, 0, 0);

    private static NodeId at(NodeId from, int dir, int n) {
        return GraphTestSupport.relative(from, dir, n);
    }

    /** Main line of 11 nodes along +x with a branch of 5 nodes leaving the middle toward +z. */
    private static Net tNetwork(Sink sink, JunctionRouter router) {
        Net net = new Net(sink, router);
        net.line(ORIGIN, E, 11);
        NodeId mid = at(ORIGIN, E, 5);
        net.graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
        net.line(at(mid, S, 5), N, 5);
        net.graph.updateConnections(at(mid, S, 1), GraphTestSupport.dirs(N, S));
        sink.terminal(at(ORIGIN, E, 10), E).terminal(at(mid, S, 5), S);
        return net;
    }

    @Test
    void anItemCrossesALineAndIsDeliveredExactlyWhenItArrives() {
        Sink sink = new Sink();
        Net net = new Net(sink, new RoundRobinRouter());
        net.line(ORIGIN, E, 11);
        NodeId end = at(ORIGIN, E, 10);
        sink.terminal(end, E);

        assertTrue(net.solver.insert(ORIGIN, E, 7));
        assertEquals(1, net.solver.liveItems());
        net.tick(19);
        assertTrue(sink.deliveries.isEmpty());
        net.tick(1);
        assertEquals(List.of("20:" + end.raw() + ":7"), sink.deliveries);
        assertEquals(0, net.solver.liveItems());
        assertTrue(net.solver.isIdle());
    }

    @Test
    void spacingAndCapacityAreEnforcedAtTheEntry() {
        Sink sink = new Sink();
        Net net = new Net(sink, new RoundRobinRouter(), new TransitConfig(0.25f, 0.5f), null, false);
        net.line(ORIGIN, E, 3); // length 2 -> 9 items fit at 0.25 spacing
        NodeId end = at(ORIGIN, E, 2);
        sink.terminal(end, E);
        sink.rejectFirst = Integer.MAX_VALUE; // nothing leaves, the queue fills up

        int accepted = 0;
        for (int i = 0; i < 40; i++) {
            if (net.solver.insert(ORIGIN, E, i)) accepted++;
            net.tick(1);
        }
        assertEquals(9, accepted);
        GraphSegment<TransitPayload> segment = onlySegment(net);
        TransitQueue q = segment.payload().forward().size() > 0 ? segment.payload().forward() : segment.payload().backward();
        assertEquals(9, q.size());
        for (int i = 1; i < q.size(); i++) {
            assertTrue(q.progressAt(i - 1) - q.progressAt(i) >= 0.25f - 1e-6f);
        }
        assertEquals(2f, q.progressAt(0));
        assertTrue(q.isBlocked());
    }

    private static GraphSegment<TransitPayload> onlySegment(Net net) {
        assertEquals(1, net.graph.segments().size());
        return net.graph.segments().iterator().next();
    }

    @Test
    void aRejectingEndpointNeverLosesTheItem() {
        Sink sink = new Sink();
        sink.rejectFirst = 5;
        Net net = new Net(sink, new RoundRobinRouter());
        net.line(ORIGIN, E, 3);
        sink.terminal(at(ORIGIN, E, 2), E);
        net.solver.insert(ORIGIN, E, 42);
        net.tick(4); // arrives on tick 4
        assertEquals(1, net.solver.liveItems());
        net.tick(20);
        assertEquals(1, sink.deliveries.size());
        assertTrue(sink.deliveries.get(0).endsWith(":42"));
        assertEquals(6, sink.offers);
        assertEquals(0, net.solver.liveItems());
    }

    @Test
    void roundRobinAlternatesBetweenExits() {
        Sink sink = new Sink();
        Net net = tNetwork(sink, new RoundRobinRouter());
        int sent = 0;
        for (int t = 0; t < 400 && sent < 10; t++) {
            if (net.solver.insert(ORIGIN, E, sent)) sent++;
            net.tick(1);
        }
        net.tick(200);
        assertEquals(10, sink.deliveries.size());
        long east = at(ORIGIN, E, 10).raw();
        long south = at(at(ORIGIN, E, 5), S, 5).raw();
        assertEquals(5, sink.byNode.get(east).size());
        assertEquals(5, sink.byNode.get(south).size());
    }

    @Test
    void priorityRouterPrefersTheHighestPriorityExitAndFilterRestricts() {
        long south = at(at(ORIGIN, E, 5), S, 5).raw();
        Sink sink = new Sink();
        Net net = tNetwork(sink, new PriorityRouter((node, dir) -> dir == S ? 10 : 0));
        for (int i = 0; i < 4; i++) {
            net.solver.insert(ORIGIN, E, i);
            net.tick(2);
        }
        net.tick(100);
        assertEquals(4, sink.byNode.get(south).size());

        // only even handles may go south, odd ones are forced east
        Sink sink2 = new Sink();
        JunctionRouter filtered = new FilterRouter((node, dir, handle) -> dir == S ? handle % 2 == 0 : handle % 2 == 1,
                new PriorityRouter((node, dir) -> dir));
        Net net2 = tNetwork(sink2, filtered);
        for (int i = 0; i < 6; i++) {
            net2.solver.insert(ORIGIN, E, i);
            net2.tick(2);
        }
        net2.tick(200);
        assertEquals(List.of(0, 2, 4), sink2.byNode.get(south));
        assertEquals(List.of(1, 3, 5), sink2.byNode.get(at(ORIGIN, E, 10).raw()));
    }

    @Test
    void anItemNoExitAcceptsWaitsAtTheJunctionWithoutBlockingForever() {
        Sink sink = new Sink();
        Net net = tNetwork(sink, new FilterRouter((n, d, h) -> false, new RoundRobinRouter()));
        net.solver.insert(ORIGIN, E, 1);
        net.tick(100);
        assertEquals(1, net.solver.liveItems());
        assertTrue(sink.deliveries.isEmpty());
    }

    @Test
    void vectorisedAdvanceMatchesAPlainLoopBitForBit() {
        TransitSolver.ProgressAdvance scalar = (p, s, n) -> {
            for (int i = 0; i < n; i++) p[i] = p[i] + s[i];
        };
        Sink a = new Sink();
        Sink b = new Sink();
        Net simd = new Net(a, new RoundRobinRouter(), new TransitConfig(0.25f, 0.37f), null, false);
        Net ref = new Net(b, new RoundRobinRouter(), new TransitConfig(0.25f, 0.37f), scalar, false);
        for (Net net : List.of(simd, ref)) {
            net.line(ORIGIN, E, 40);
            net.graph.updateConnections(at(ORIGIN, E, 20), GraphTestSupport.dirs(E, W, S));
            net.line(at(at(ORIGIN, E, 20), S, 30), N, 30);
            net.graph.updateConnections(at(at(ORIGIN, E, 20), S, 1), GraphTestSupport.dirs(N, S));
        }
        a.terminal(at(ORIGIN, E, 39), E).terminal(at(at(ORIGIN, E, 20), S, 30), S);
        b.terminal(at(ORIGIN, E, 39), E).terminal(at(at(ORIGIN, E, 20), S, 30), S);

        java.util.Random random = new java.util.Random(7);
        for (int t = 0; t < 600; t++) {
            if (t % 3 == 0) {
                float speed = 0.05f + random.nextFloat() * 0.3f;
                boolean x = simd.solver.insert(ORIGIN, E, t, speed);
                boolean y = ref.solver.insert(ORIGIN, E, t, speed);
                assertEquals(x, y);
            }
            simd.tick(1);
            ref.tick(1);
            assertEquals(snapshot(simd), snapshot(ref), "diverged at tick " + t);
        }
        assertEquals(a.deliveries, b.deliveries);
        assertFalse(a.deliveries.isEmpty());
    }

    private static String snapshot(Net net) {
        StringBuilder sb = new StringBuilder();
        for (GraphSegment<TransitPayload> s : net.graph.segments()) {
            for (TransitQueue q : new TransitQueue[]{s.payload().forward(), s.payload().backward()}) {
                sb.append(s.id()).append('[');
                for (int i = 0; i < q.size(); i++) {
                    sb.append(q.handleAt(i)).append('@').append(Float.floatToRawIntBits(q.progressAt(i))).append(',');
                }
                sb.append(']');
            }
        }
        return sb.toString();
    }

    @Test
    void theOutcomeDoesNotDependOnTheOrderTheNetworkWasBuilt() {
        List<String> first = runMerge(false);
        List<String> second = runMerge(true);
        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    /** Two feeder lines merge into one exit: contested slots must resolve identically however segments are stored. */
    private List<String> runMerge(boolean reverseBuildOrder) {
        Sink sink = new Sink();
        Net net = new Net(sink, new FilterRouter((n, d, h) -> d == E, new RoundRobinRouter()));
        NodeId hub = at(ORIGIN, E, 5);
        NodeId westStart = ORIGIN;
        NodeId northStart = at(hub, N, 5);
        NodeId eastEnd = at(hub, E, 5);
        Runnable west = () -> {
            net.line(westStart, E, 5);
            net.graph.updateConnections(at(westStart, E, 4), GraphTestSupport.dirs(E, W));
        };
        Runnable north = () -> {
            net.line(northStart, S, 5);
            net.graph.updateConnections(at(hub, N, 1), GraphTestSupport.dirs(S, N));
        };
        Runnable east = () -> {
            net.line(at(hub, E, 5), W, 5);
            net.graph.updateConnections(at(hub, E, 1), GraphTestSupport.dirs(E, W));
        };
        if (reverseBuildOrder) {
            east.run();
            north.run();
            west.run();
        } else {
            west.run();
            north.run();
            east.run();
        }
        net.graph.addNode(hub, GraphTestSupport.dirs(E, W, N));
        sink.terminal(eastEnd, E);
        int h = 0;
        for (int t = 0; t < 300; t++) {
            if (t % 2 == 0) {
                net.solver.insert(westStart, E, h++, 0.4f);
                net.solver.insert(northStart, S, h++, 0.4f);
            }
            net.tick(1);
        }
        net.tick(300);
        List<String> out = new ArrayList<>(sink.deliveries);
        return out;
    }

    @Test
    void migrationKeepsItemsMovingAcrossASplitAndAMerge() {
        Sink sink = new Sink();
        Net net = new Net(sink, new RoundRobinRouter(), new TransitConfig(0.25f, 0.2f), null, true);
        net.line(ORIGIN, E, 21);
        NodeId end = at(ORIGIN, E, 20);
        sink.terminal(end, E);
        NodeId mid = at(ORIGIN, E, 10);

        int sent = 0;
        for (int t = 0; t < 120; t++) {
            if (t % 5 == 0 && net.solver.insert(ORIGIN, E, sent)) sent++;
            net.tick(1);
            if (t == 40) net.graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S)); // split
            if (t == 80) net.graph.updateConnections(mid, GraphTestSupport.dirs(E, W));     // merge
        }
        net.tick(300);
        List<Integer> got = new ArrayList<>(sink.byNode.get(end.raw()));
        assertTrue(net.lost.isEmpty(), "lost: " + net.lost);
        assertEquals(sent, got.size(), "every inserted item is delivered once");
        // order is preserved on a single line
        List<Integer> sorted = new ArrayList<>(got);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, got);
        assertEquals(0, net.solver.liveItems());
    }

    @Test
    void removingAndRestoringAnEndKeepsItemsInFlight() {
        Sink sink = new Sink();
        Net net = new Net(sink, new RoundRobinRouter(), new TransitConfig(0.25f, 0.5f), null, true);
        net.line(ORIGIN, E, 11);
        sink.terminal(at(ORIGIN, E, 9), E);
        for (int i = 0; i < 4; i++) {
            net.solver.insert(ORIGIN, E, i);
            net.tick(4);
        } // items at progress 3.5.. spread over the first blocks
        net.graph.removeNode(at(ORIGIN, E, 10));
        net.graph.updateConnections(at(ORIGIN, E, 9), GraphTestSupport.dirs(W));
        net.tick(100);
        assertEquals(4, sink.deliveries.size() + net.lost.size());
        assertTrue(net.lost.isEmpty());
    }

    @Test
    void anItemOnAVanishedBlockIsReportedLostOnTheNextTick() {
        Sink sink = new Sink();
        Net net = new Net(sink, new RoundRobinRouter(), new TransitConfig(0.25f, 0.5f), null, true);
        net.line(ORIGIN, E, 11);
        net.solver.insert(ORIGIN, E, 5);
        net.tick(30); // no terminal: it waits at the far end
        assertEquals(1, net.solver.liveItems());

        net.graph.removeNode(at(ORIGIN, E, 10));
        net.graph.updateConnections(at(ORIGIN, E, 9), GraphTestSupport.dirs(W));
        assertTrue(net.lost.isEmpty(), "reported at the next tick, so a follow-up mutation can still save it");
        net.tick(1);
        assertEquals(List.of(5), net.lost);
        assertEquals(0, net.solver.liveItems());
    }

    @Test
    void ticksDoNotAllocateOnceWarm() {
        // a sink that records nothing: the test's own bookkeeping must not count as the solver's allocation
        Sink sink = new Sink() {
            @Override
            public boolean deliver(long nodeRaw, int direction, int handle) {
                return true;
            }
        };
        Net net = tNetwork(sink, new RoundRobinRouter());
        int sent = 0;
        for (int t = 0; t < 4000; t++) {
            if (t % 2 == 0) net.solver.insert(ORIGIN, E, sent++);
            net.tick(1);
        }
        for (int t = 0; t < 8000; t++) {
            if (t % 2 == 0) net.solver.insert(ORIGIN, E, sent++);
            net.tick(1);
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long perTick = Long.MAX_VALUE;
        for (int window = 0; window < 6; window++) {
            long before = threads.getThreadAllocatedBytes(tid);
            for (int t = 0; t < 1000; t++) {
                if (t % 2 == 0) net.solver.insert(ORIGIN, E, sent++);
                net.tick(1);
            }
            perTick = Math.min(perTick, (threads.getThreadAllocatedBytes(tid) - before) / 1000);
        }
        assertTrue(perTick < 64, "tick allocated " + perTick + " bytes");
    }
}
