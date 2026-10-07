package bench;

import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.CellClass;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.openjdk.jmh.annotations.*;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * One tick of N dropped items (gravity, drag, collision with blocks, ground
 * friction: vanilla's item movement rules) in a random 48x32x48 block world.
 * "java" is Celeris's pure-Java kernel, which runs vanilla's math one item at a
 * time; "native" the C++ kernel (AVX2 here). The items are re-thrown every
 * 40 ticks so the batch never just sits still. -Dceleris.physics.threads sets
 * the worker threads (0: the calling thread only).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class PhysicsBench {

    @Param({"1000", "10000", "32768"})
    public int items;

    @Param({"java", "native"})
    public String kernel;

    PhysicsFactory factory;
    TerrainView terrain;
    BodyBatch batch;
    int tick;

    @Setup
    public void setup() {
        factory = "native".equals(kernel) ? PhysicsTestAccess.nativeFactory() : PhysicsTestAccess.javaFactory();
        terrain = world(factory, 7);
        batch = factory.newBatch(items, PhysicsMode.VANILLA, 0);
        throwItems(1);
    }

    @TearDown
    public void tearDown() throws Exception {
        batch.close();
        terrain.close();
    }

    private void throwItems(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        boolean fresh = batch.size() == 0;
        for (int i = 0; i < items; i++) {
            double x = -14 + r.nextDouble() * 44, y = 3 + r.nextDouble() * 26, z = -14 + r.nextDouble() * 44;
            double vx = (r.nextDouble() - 0.5) * 0.8, vy = (r.nextDouble() - 0.5) * 1.2, vz = (r.nextDouble() - 0.5) * 0.8;
            if (fresh) {
                batch.add(BodyParams.ITEM, 0.25F, 0.25F, x, y, z, vx, vy, vz, false, i & 3);
            } else {
                batch.setPosition(i, x, y, z);
                batch.setVelocity(i, vx, vy, vz);
                batch.setState(i, false, false, i & 3);
            }
        }
    }

    @Benchmark
    public int tick() {
        if (++tick % 40 == 0) {
            throwItems(tick);
        }
        return batch.step(terrain, 0);
    }

    static TerrainView world(PhysicsFactory factory, long seed) {
        TerrainView t = factory.newTerrain(3, 2, 3);
        t.setOrigin(-1, 0, -1);
        byte[] air = new byte[TerrainView.SECTION_BYTES];
        for (int sx = -1; sx <= 1; sx++) {
            for (int sy = 0; sy <= 1; sy++) {
                for (int sz = -1; sz <= 1; sz++) {
                    t.setSection(sx, sy, sz, air, 0);
                }
            }
        }
        SplittableRandom r = new SplittableRandom(seed);
        int ice = t.solidClass(0.98F);
        for (int x = -16; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = -16; z < 32; z++) {
                    double v = r.nextDouble();
                    if (y < 3 || v < 0.06) {
                        t.setCell(x, y, z, v < 0.01 ? ice : CellClass.SOLID_DEFAULT);
                    }
                }
            }
        }
        return t;
    }
}
