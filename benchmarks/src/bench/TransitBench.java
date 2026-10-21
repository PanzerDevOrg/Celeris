package bench;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
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

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * One server tick of items circulating on square loops of pipe (20 blocks a side, junctions at the corners), the
 * steady state of a busy logistics network. {@code solver} is Celeris's {@link TransitSolver} (flat arrays, advance
 * through VectorOperations); {@code scalar} is the same solver with a plain loop for the advance; {@code objects}
 * is what a mod would write otherwise: one small object per item in an {@code ArrayList} per pipe, moved item by item.
 * All three apply the same rules (speed, spacing, hand-over at the corners).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class TransitBench {

    static int SIDE = 20;
    static final float SPACING = 0.25f;
    static final float SPEED = 0.1f;

    @Param({"solver", "scalar", "objects"})
    public String impl;

    @Param({"2000", "20000", "100000"})
    public int items;

    /** Blocks per side of each loop: 20 is a busy base, 200 a long trunk line. */
    @Param({"20"})
    public int side;

    private TransitSolver solver;
    private Objects objects;

    @Setup(Level.Trial)
    public void setup() {
        SIDE = side;
        int rings = Math.max(1, items / (4 * SIDE));
        if (SIDE > 64) rings = Math.max(1, items / (4 * SIDE));
        if (impl.equals("objects")) {
            objects = new Objects(rings * 4, items / (rings * 4));
            return;
        }
        SegmentedGraphImpl<TransitPayload> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new);
        solver = impl.equals("scalar")
                ? new TransitSolver(new TransitConfig(SPACING, SPEED), new RoundRobinRouter(), TransitEndpoints.NONE, null,
                (p, s, n) -> {
                    for (int i = 0; i < n; i++) p[i] = p[i] + s[i];
                })
                : new TransitSolver(new TransitConfig(SPACING, SPEED), new RoundRobinRouter(), TransitEndpoints.NONE);
        solver.bind(graph);
        for (int r = 0; r < rings; r++) {
            buildRing(graph, r * 2);
        }
        int perSegment = items / (rings * 4);
        for (int k = 0; k < perSegment; k++) {
            for (int r = 0; r < rings; r++) {
                insertAll(r * 2);
            }
            for (int t = 0; t < 3; t++) solver.tick();
        }
        for (int t = 0; t < 50; t++) solver.tick();
    }

    // directions: 2=NORTH(-z) 3=SOUTH(+z) 4=WEST(-x) 5=EAST(+x)
    private void insertAll(int row) {
        NodeId c0 = NodeId.pack(0, 0, row * (SIDE + 2));
        NodeId c1 = NodeId.pack(SIDE, 0, row * (SIDE + 2));
        NodeId c2 = NodeId.pack(SIDE, 0, row * (SIDE + 2) + SIDE);
        NodeId c3 = NodeId.pack(0, 0, row * (SIDE + 2) + SIDE);
        solver.insert(c0, 5, 1);
        solver.insert(c1, 3, 1);
        solver.insert(c2, 4, 1);
        solver.insert(c3, 2, 1);
    }

    private static void buildRing(SegmentedGraphImpl<TransitPayload> g, int row) {
        int z0 = row * (SIDE + 2);
        for (int i = 0; i <= SIDE; i++) {
            ConnectionMask top = i == 0 ? mask(5, 3) : i == SIDE ? mask(4, 3) : mask(4, 5);
            g.addNode(NodeId.pack(i, 0, z0), top);
            ConnectionMask bottom = i == 0 ? mask(5, 2) : i == SIDE ? mask(4, 2) : mask(4, 5);
            g.addNode(NodeId.pack(i, 0, z0 + SIDE), bottom);
        }
        for (int j = 1; j < SIDE; j++) {
            g.addNode(NodeId.pack(0, 0, z0 + j), mask(2, 3));
            g.addNode(NodeId.pack(SIDE, 0, z0 + j), mask(2, 3));
        }
    }

    private static ConnectionMask mask(int a, int b) {
        return ConnectionMask.of((1 << a) | (1 << b));
    }

    @Benchmark
    public int tick() {
        if (objects != null) {
            return objects.tick();
        }
        solver.tick();
        return solver.liveItems();
    }

    /** The object-per-item baseline, on the same four-pipe loops. */
    static final class Objects {
        static final class Item {
            float progress;
            final float speed;

            Item(float progress, float speed) {
                this.progress = progress;
                this.speed = speed;
            }
        }

        final ArrayList<ArrayList<Item>> pipes = new ArrayList<>();
        final int maxPerPipe = (int) (SIDE / SPACING) + 1;
        final int pipeCount;

        Objects(int pipeCount, int perPipe) {
            this.pipeCount = pipeCount;
            for (int p = 0; p < pipeCount; p++) {
                ArrayList<Item> list = new ArrayList<>();
                for (int i = 0; i < perPipe; i++) {
                    list.add(new Item(SIDE - i * 0.3f - 0.01f * (i + 1), SPEED));
                }
                pipes.add(list);
            }
        }

        int tick() {
            int moved = 0;
            for (int p = 0; p < pipeCount; p++) {
                ArrayList<Item> list = pipes.get(p);
                float limit = SIDE;
                for (int i = 0; i < list.size(); i++) {
                    Item it = list.get(i);
                    float v = it.progress + it.speed;
                    if (v > limit) v = limit;
                    it.progress = v;
                    limit = Math.max(0f, v - SPACING);
                }
                if (!list.isEmpty() && list.get(0).progress >= SIDE) {
                    ArrayList<Item> next = pipes.get(p % 4 == 3 ? p - 3 : p + 1);
                    if (next.size() < maxPerPipe && (next.isEmpty() || next.get(next.size() - 1).progress - SPACING >= 0f)) {
                        Item head = list.remove(0);
                        head.progress = 0f;
                        next.add(head);
                        moved++;
                    }
                }
            }
            return moved;
        }
    }
}
