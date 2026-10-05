import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.ChunkWorkers;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

class ChunkWorkersTest {

    @Test
    void everyChunkRunsExactlyOnceAcrossManyRuns() {
        try (ChunkWorkers w = new ChunkWorkers(3)) {
            for (int run = 0; run < 2000; run++) {
                int n = 1 + run % 37;
                AtomicIntegerArray hits = new AtomicIntegerArray(n);
                w.run(hits::incrementAndGet, n);
                for (int c = 0; c < n; c++) {
                    assertEquals(1, hits.get(c), "run " + run + " chunk " + c);
                }
            }
        }
    }

    @Test
    void parallelStepEqualsSequentialStep() {
        // Above the parallel threshold, so the shared worker pool runs the chunks.
        int n = 20_000;
        PhysicsFactory f = PhysicsTestAccess.javaFactory(false);
        try (TerrainView t = PhysicsTestSupport.randomWorld(f, 21);
             BodyBatch parallel = f.newBatch(n, PhysicsMode.VANILLA, 0);
             BodyBatch serial = f.newBatch(n, PhysicsMode.VANILLA, 0)) {
            PhysicsTestSupport.randomBodies(parallel, n, 9);
            PhysicsTestSupport.randomBodies(serial, 1024, 9);
            for (int tick = 0; tick < 5; tick++) {
                parallel.step(t, 0);
                serial.step(t, 0);
            }
            // The first chunk of the big batch saw exactly the same bodies as the small one.
            for (int i = 0; i < 1024; i++) {
                assertEquals(serial.x(i), parallel.x(i));
                assertEquals(serial.vy(i), parallel.vy(i));
                assertEquals(serial.flags(i), parallel.flags(i));
            }
        }
    }
}
