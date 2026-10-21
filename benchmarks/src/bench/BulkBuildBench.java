package bench;

import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
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
 * Building a network of 10,000 pipe blocks (a chunk load, a world load): adding them one by one with
 * {@code addNode} plus neighbour updates, as a mod does today, against one {@code addNodes} call. {@code line} is a single straight
 * run (every added block extends it, the worst case for one-by-one fusion); {@code grid} is 100 rows of 100
 * blocks with a junction every 20 blocks and rows linked at the junctions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BulkBuildBench {

    @Param({"line", "grid"})
    public String layout;

    @Param({"oneByOne", "bulk"})
    public String mode;

    long[] ids;
    int[] bits;

    @Setup(Level.Trial)
    public void setup() {
        int n = 10_000;
        ids = new long[n];
        bits = new int[n];
        int E = 1 << 5, W = 1 << 4, N = 1 << 2, S = 1 << 3;
        if (layout.equals("line")) {
            for (int i = 0; i < n; i++) {
                ids[i] = NodeId.packRaw(i, 0, 0);
                bits[i] = i == 0 ? E : i == n - 1 ? W : E | W;
            }
        } else {
            int k = 0;
            for (int row = 0; row < 100; row++) {
                for (int col = 0; col < 100; col++) {
                    int b = (col == 0 ? 0 : W) | (col == 99 ? 0 : E);
                    if (col % 20 == 0 || col == 99) {
                        b |= (row == 0 ? 0 : N) | (row == 99 ? 0 : S);
                    }
                    ids[k] = NodeId.packRaw(col, 0, row);
                    bits[k++] = b;
                }
            }
        }
    }

    @Benchmark
    public int build() {
        SegmentedGraphImpl<Object> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), Object::new);
        if (mode.equals("bulk")) {
            graph.addNodes(ids, bits, ids.length);
        } else {
            // What a mod without a bulk call does: place a block with the connections to the blocks already
            // there, then update each of those neighbours so they connect back.
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap placed = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            placed.defaultReturnValue(-1);
            for (int i = 0; i < ids.length; i++) {
                int mine = 0;
                for (int d = 0; d < 6; d++) {
                    if ((bits[i] & (1 << d)) == 0) continue;
                    long neighbour = NodeId.stepRaw(ids[i], d);
                    int theirs = placed.get(neighbour);
                    if (theirs >= 0) {
                        mine |= 1 << d;
                        int back = 1 << (d ^ 1);
                        if ((theirs & back) == 0) {
                            placed.put(neighbour, theirs | back);
                            graph.updateConnections(new NodeId(neighbour), ConnectionMask.of(theirs | back));
                        }
                    }
                }
                placed.put(ids[i], mine);
                graph.addNode(new NodeId(ids[i]), ConnectionMask.of(mine));
            }
        }
        return graph.segmentCount();
    }
}
