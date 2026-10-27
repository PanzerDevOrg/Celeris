import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import net.bichal.mods.celeris.physics.ChunkWorkers;
import net.bichal.mods.celeris.pipeline.transit.RoundRobinRouter;
import net.bichal.mods.celeris.pipeline.transit.TransitConfig;
import net.bichal.mods.celeris.pipeline.transit.TransitEndpoints;
import net.bichal.mods.celeris.pipeline.transit.TransitEvents;
import net.bichal.mods.celeris.pipeline.transit.TransitPayload;
import net.bichal.mods.celeris.pipeline.transit.TransitQueue;
import net.bichal.mods.celeris.pipeline.transit.TransitSolver;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways the solver saves work on big networks, skipping queues that cannot move and sharing the pass over
 * the items with workers, must give exactly what walking every item on one thread gives: same positions, same
 * deliveries, same events, tick after tick.
 */
class TransitScaleTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int N = GraphTestSupport.NORTH;
    private static final int S = GraphTestSupport.SOUTH;
    private static final int ROWS = 12;
    private static final int ROW_END = 40;

    /** Accepts a deterministic part of the offers, so queues block, wait and are released again. */
    private static final class Sink implements TransitEndpoints {
        final Random random;
        double acceptance;
        long offers;
        long delivered;
        long digest;

        Sink(long seed, double acceptance) {
            this.random = new Random(seed);
            this.acceptance = acceptance;
        }

        @Override
        public int terminalMask(long nodeRaw) {
            return NodeId.xOf(nodeRaw) == ROW_END ? 1 << E : 0;
        }

        @Override
        public boolean deliver(long nodeRaw, int direction, int handle) {
            offers++;
            if (random.nextDouble() >= acceptance) {
                return false;
            }
            delivered++;
            digest = digest * 31 + handle * 7L + nodeRaw;
            return true;
        }
    }

    private static final class Digest implements TransitEvents {
        long value;

        @Override
        public void itemEntered(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
            value = value * 31 + segmentId * 3 + handle + Float.floatToIntBits(progress) + (forward ? 1 : 0) + tick * 17;
        }

        @Override
        public void itemLeft(long segmentId, boolean forward, int handle, int reason, long tick) {
            value = value * 31 + segmentId * 5 + handle + reason * 13L + tick * 19;
        }

        @Override
        public void queueBlocked(long segmentId, boolean forward, boolean blocked, long tick) {
            value = value * 31 + segmentId * 7 + (blocked ? 1 : 2) + tick * 23;
        }
    }

    private static final class Net {
        final SegmentedGraphImpl<TransitPayload> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new);
        final TransitSolver solver;
        final Sink sink;
        final Digest events = new Digest();
        boolean odd;

        Net(double acceptance) {
            sink = new Sink(42, acceptance);
            solver = new TransitSolver(new TransitConfig(0.25f, 0.1f), new RoundRobinRouter(), sink);
            solver.bind(graph);
            solver.setEvents(events);
            // A spine along z with a row of pipes leaving every second block: a junction per row.
            for (int z = 0; z < ROWS * 2; z++) {
                graph.addNode(NodeId.pack(0, 0, z), z % 2 == 0
                        ? GraphTestSupport.dirs(E, N, S) : GraphTestSupport.dirs(N, S));
            }
            graph.updateConnections(NodeId.pack(0, 0, 0), GraphTestSupport.dirs(E, S));
            graph.updateConnections(NodeId.pack(0, 0, ROWS * 2 - 1), GraphTestSupport.dirs(N));
            for (int row = 0; row < ROWS; row++) {
                for (int x = 1; x <= ROW_END; x++) {
                    graph.addNode(NodeId.pack(x, 0, row * 2), x == ROW_END ? GraphTestSupport.dirs(W) : GraphTestSupport.dirs(E, W));
                }
            }
        }

        /** The same inserts, with a mix of speeds, whatever the optimisation. */
        void feed(Random random, int tick) {
            for (int row = 0; row < ROWS; row++) {
                if (random.nextInt(3) == 0) {
                    float speed = new float[] {0.1f, 0.1f, 0.2f, 0.05f, -0.02f}[random.nextInt(odd ? 5 : 4)];
                    solver.insert(NodeId.pack(0, 0, row * 2), E, tick * 100 + row, speed);
                }
            }
        }

        long state() {
            long h = 17;
            for (int i = 0; i < graph.segmentCount(); i++) {
                GraphSegment<TransitPayload> segment = graph.segmentByIndex(i);
                for (TransitQueue queue : new TransitQueue[] {segment.payload().forward(), segment.payload().backward()}) {
                    h = h * 31 + queue.size() + (queue.isBlocked() ? 1 : 0);
                    for (int k = 0; k < queue.size(); k++) {
                        h = h * 31 + queue.handleAt(k);
                        h = h * 31 + Float.floatToIntBits(queue.progressAt(k));
                        h = h * 31 + Float.floatToIntBits(queue.speedAt(k));
                    }
                }
            }
            return h;
        }
    }

    private static void runAgainstReference(double acceptance, int ticks, java.util.function.Consumer<Net> setup) {
        runAgainstReference(acceptance, ticks, setup, false);
    }

    private static void runAgainstReference(double acceptance, int ticks, java.util.function.Consumer<Net> setup, boolean odd) {
        Net reference = new Net(acceptance);
        reference.solver.setSkipSettled(false);
        reference.odd = odd;
        Net other = new Net(acceptance);
        setup.accept(other);
        Random a = new Random(7);
        Random b = new Random(7);
        for (int t = 0; t < ticks; t++) {
            if (t < ticks * 2 / 3) {
                reference.feed(a, t);
                other.feed(b, t);
            }
            reference.solver.tick();
            other.solver.tick();
            assertEquals(reference.state(), other.state(), "queues differ at tick " + t);
            assertEquals(reference.events.value, other.events.value, "events differ at tick " + t);
            assertEquals(reference.sink.digest, other.sink.digest, "deliveries differ at tick " + t);
            assertEquals(reference.solver.liveItems(), other.solver.liveItems());
        }
        assertTrue(reference.sink.delivered > 0 || acceptance == 0.0, "the scenario moves items");
    }

    @Test
    void skippingQueuesThatCannotMoveChangesNothing() {
        for (double acceptance : new double[] {0.0, 0.02, 0.3, 1.0}) {
            runAgainstReference(acceptance, 1500, net -> { });
        }
    }

    @Test
    void itemsWithOddSpeedsKeepTheirQueueMoving() {
        for (double acceptance : new double[] {0.0, 0.2}) {
            runAgainstReference(acceptance, 1200, net -> net.odd = true, true);
        }
    }

    @Test
    void aFullNetworkWithNoConsumerSettlesAndWakesWhenOneAppears() {
        Net net = new Net(0.0);
        Random random = new Random(3);
        for (int t = 0; t < 1200; t++) {
            if (t < 700) {
                net.feed(random, t);
            }
            net.solver.tick();
        }
        assertTrue(net.solver.liveItems() > 100);
        long before = net.state();
        net.solver.tick();
        assertEquals(before, net.state(), "nothing moves while nothing is accepted");
        assertEquals(0, net.sink.delivered);

        net.sink.acceptance = 1.0;
        for (int t = 0; t < 60; t++) {
            net.solver.tick();
        }
        assertTrue(net.sink.delivered > 0, "settled queues wake up when a consumer accepts");
    }

    @Test
    void sharingThePassWithWorkersChangesNothing() {
        try (ChunkWorkers workers = new ChunkWorkers(3)) {
            for (double acceptance : new double[] {0.0, 0.1, 1.0}) {
                runAgainstReference(acceptance, 1200, net -> net.solver.setWorkers(workers, 1));
            }
        }
    }

    @Test
    void workersAndSkippingTogetherMatchTheSingleThreadedFullPass() {
        try (ChunkWorkers workers = new ChunkWorkers(2)) {
            runAgainstReference(0.05, 2000, net -> net.solver.setWorkers(workers, 1));
        }
    }

    @Test
    void busyWorkersLeaveTheTickToTheCaller() {
        try (ChunkWorkers workers = new ChunkWorkers(1)) {
            Net net = new Net(1.0);
            net.solver.setWorkers(workers, 1);
            Random random = new Random(1);
            // A run in progress on this very pool (from inside a chunk task) makes tryRun refuse.
            workers.run(chunk -> {
                net.feed(random, 0);
                net.solver.tick();
            }, 1);
            assertTrue(net.solver.liveItems() > 0, "the tick ran on the calling thread");
        }
    }
}
