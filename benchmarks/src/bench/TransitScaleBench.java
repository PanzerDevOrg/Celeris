package bench;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import com.panzer.mods.celeris.physics.ChunkWorkers;
import com.panzer.mods.celeris.pipeline.transit.RoundRobinRouter;
import com.panzer.mods.celeris.pipeline.transit.TransitConfig;
import com.panzer.mods.celeris.pipeline.transit.TransitEndpoints;
import com.panzer.mods.celeris.pipeline.transit.TransitPayload;
import com.panzer.mods.celeris.pipeline.transit.TransitSolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * One server tick with 100,000 to 8 million items in flight on straight lines of pipe, 100 blocks long, each ending at
 * a consumer. {@code flow}: the consumer takes everything and every line is fed an item every third tick, so every
 * item keeps moving (the feeding is part of the measured tick). {@code blocked}: the consumer refuses everything and
 * the lines were filled until no entry has room, the steady state of a logistics network whose consumers are full:
 * no item can move and every head waits at the exit. {@code threads} shares the pass over the items with workers.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class TransitScaleBench {

    static final int SIDE = 100;
    static final float SPACING = 0.25f;
    static final float SPEED = 0.1f;

    /** Sizes the network; the items really in flight are printed by the setup (about half of this when flowing). */
    @Param({"100000", "1000000", "4000000"})
    public int items;

    @Param({"flow", "blocked"})
    public String load;

    /** Worker threads sharing the pass over the items (0: the calling thread only). */
    @Param({"0", "3"})
    public int threads;

    private TransitSolver solver;
    private SegmentedGraphImpl<TransitPayload> graphRef;

    private int lines;
    private int phase;

    @Setup(Level.Trial)
    public void setup() {
        boolean blocked = load.equals("blocked");
        int perSegmentMax = (int) (SIDE / SPACING) + 1;
        TransitEndpoints endpoints = new TransitEndpoints() {
            @Override
            public int terminalMask(long nodeRaw) {
                return NodeId.xOf(nodeRaw) == SIDE ? 1 << 5 : 0;
            }

            @Override
            public boolean deliver(long nodeRaw, int direction, int handle) {
                return !blocked;
            }
        };
        // About 300 items per line when flowing (one inserted every third tick), 401 when full.
        lines = Math.max(1, items / (blocked ? perSegmentMax : 300));
        SegmentedGraphImpl<TransitPayload> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new);
        solver = new TransitSolver(new TransitConfig(SPACING, SPEED), new RoundRobinRouter(), endpoints);
        graphRef = graph;
        solver.bind(graph);
        if (threads > 0) {
            // By reflection so the same benchmark also runs against a build that predates the workers.
            try {
                TransitSolver.class.getMethod("setWorkers", ChunkWorkers.class, int.class)
                        .invoke(solver, new ChunkWorkers(threads), 200_000);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this build has no parallel pass", e);
            }
        }
        for (int r = 0; r < lines; r++) {
            buildLine(graph, r);
        }
        // Flowing: feed until the lines hold their steady load (the first items have crossed). Blocked: feed until
        // no entry has room left, so every queue is full from its exit back to its entry and nothing can move.
        int ticks = blocked ? 4 * perSegmentMax * 3 : (int) (SIDE / SPEED) + 300;
        for (int t = 0; t < ticks; t++) {
            feed();
        }
        System.out.println("lines " + lines + " items in flight " + solver.liveItems());
    }

    /** One tick; flowing lines get an item every third tick, staggered so the work is the same every tick. */
    private void feed() {
        for (int r = phase % 3; r < lines; r += 3) {
            solver.insert(NodeId.pack(0, 0, r * 2), 5, 1);
        }
        phase++;
        solver.tick();
    }

    @org.openjdk.jmh.annotations.TearDown(Level.Trial)
    public void report() {
        double before = progressSum(graphRef);
        solver.tick();
        System.out.println("after the run: " + solver.liveItems() + " items, progress moved in a tick " + (progressSum(graphRef) - before));
    }

    private static double progressSum(SegmentedGraphImpl<TransitPayload> graph) {
        double sum = 0;
        for (int i = 0; i < graph.segmentCount(); i++) {
            var payload = graph.segmentByIndex(i).payload();
            for (int k = 0; k < payload.forward().size(); k++) sum += payload.forward().progressAt(k);
            for (int k = 0; k < payload.backward().size(); k++) sum += payload.backward().progressAt(k);
        }
        return sum;
    }

    private static void buildLine(SegmentedGraphImpl<TransitPayload> g, int row) {
        for (int i = 0; i <= SIDE; i++) {
            g.addNode(NodeId.pack(i, 0, row * 2), i == 0 ? mask(5, 5) : i == SIDE ? mask(4, 4) : mask(4, 5));
        }
    }

    private static ConnectionMask mask(int a, int b) {
        return ConnectionMask.of((1 << a) | (1 << b));
    }

    @Benchmark
    public int tick() {
        if (load.equals("flow")) {
            feed();
        } else {
            solver.tick();
        }
        return solver.liveItems();
    }
}
