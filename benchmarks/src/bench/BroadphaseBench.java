package bench;

import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import org.openjdk.jmh.annotations.*;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Item piles: n items dropped into one block, so nearly every pair overlaps
 * (~n^2/2 pairs). "stop" is what BodyBatch.broadphase does since 0.2.3 (the
 * scan ends once pairCapacity pairs are kept); "full" counts every pair, as
 * every call did before. pairCapacity = n.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BroadphaseBench {

    @Param({"4096", "16384"})
    public int items;

    @Param({"java", "native"})
    public String kernel;

    @Param({"stop", "full"})
    public String scan;

    BodyBatch batch;
    boolean stop;

    @Setup
    public void setup() {
        PhysicsFactory f = "native".equals(kernel) ? PhysicsTestAccess.nativeFactory() : PhysicsTestAccess.javaFactory();
        batch = f.newBatch(items, PhysicsMode.VANILLA, items);
        SplittableRandom r = new SplittableRandom(1);
        for (int i = 0; i < items; i++) {
            batch.add(BodyParams.ITEM, 0.25F, 0.25F, r.nextDouble(), r.nextDouble(), r.nextDouble(), 0, 0, 0, false, 0);
        }
        stop = "stop".equals(scan);
    }

    @TearDown
    public void tearDown() {
        batch.close();
    }

    @Benchmark
    public int broadphase() {
        return batch.broadphase(0.5, stop);
    }
}
