import net.bichal.mods.celeris.physics.BodyBatch;
import net.bichal.mods.celeris.physics.PhysicsFactory;
import net.bichal.mods.celeris.physics.PhysicsJniTestAccess;
import net.bichal.mods.celeris.physics.PhysicsMode;
import net.bichal.mods.celeris.physics.PhysicsTestAccess;
import net.bichal.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step time of the three physics engines (pure Java, FFM, JNI) on one thread,
 * for a unit of work of 1024 bodies (the largest chunk, where the call is
 * amortised) and for 16 bodies (the smallest legal one, where the cost of the
 * call itself shows). Excluded from {@code test}; run with
 * {@code ./gradlew benchmarkTest}. FFM is measured only where the JVM has it.
 */
@Tag("benchmark")
class PhysicsBackendBenchmarkTest {

    private static final int TOTAL_BODIES = 100_000;
    private static final int WARMUP_TICKS = 40;
    private static final int MEASURED_TICKS = 40;
    private static final int TRIALS = 5;

    @Test
    void compareEnginesPerBody() {
        Map<String, PhysicsFactory> engines = new LinkedHashMap<>();
        engines.put("java", PhysicsTestAccess.javaFactory());
        try {
            engines.put("ffm", PhysicsTestAccess.nativeFactory());
        } catch (Throwable ignored) {
            System.out.println("ffm: not available in this JVM");
        }
        try {
            engines.put("jni", PhysicsJniTestAccess.jniFactory());
        } catch (Throwable t) {
            assumeTrue(false, "celeris_physics not built for this platform: " + t);
        }
        for (int unit : new int[] {1024, 16}) {
            System.out.printf("%n%d bodies in batches of %d, one thread, ns per body per tick (best of %d trials):%n",
                    TOTAL_BODIES, unit, TRIALS);
            for (Map.Entry<String, PhysicsFactory> e : engines.entrySet()) {
                double ns = nsPerBody(e.getValue(), unit);
                System.out.printf("  %-5s %8.2f ns/body   (%.2f ms per tick)%n", e.getKey(), ns, ns * TOTAL_BODIES / 1e6);
            }
        }
    }

    private static double nsPerBody(PhysicsFactory factory, int unit) {
        List<BodyBatch> batches = new ArrayList<>();
        try (TerrainView terrain = PhysicsTestSupport.randomWorld(factory, 11)) {
            for (int left = TOTAL_BODIES, seed = 5; left > 0; left -= unit, seed++) {
                BodyBatch b = factory.newBatch(unit, PhysicsMode.VANILLA, 0);
                PhysicsTestSupport.randomBodies(b, Math.min(unit, left), seed);
                batches.add(b);
            }
            long sink = 0;
            for (int t = 0; t < WARMUP_TICKS; t++) {
                for (BodyBatch b : batches) {
                    sink += b.step(terrain, 0);
                }
            }
            long best = Long.MAX_VALUE;
            for (int trial = 0; trial < TRIALS; trial++) {
                long start = System.nanoTime();
                for (int t = 0; t < MEASURED_TICKS; t++) {
                    for (BodyBatch b : batches) {
                        sink += b.step(terrain, 0);
                    }
                }
                best = Math.min(best, System.nanoTime() - start);
            }
            if (sink == Long.MIN_VALUE) {
                System.out.println(sink);
            }
            return (double) best / MEASURED_TICKS / TOTAL_BODIES;
        } finally {
            batches.forEach(BodyBatch::close);
        }
    }
}
