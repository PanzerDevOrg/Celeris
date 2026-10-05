import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyFlags;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of the Java reference kernel (the specification the native
 * kernel is checked against), on hand-built scenes with values worked out
 * from vanilla's ItemEntity/Entity.move arithmetic.
 */
class PhysicsKernelReferenceTest {

    private final PhysicsFactory factory = PhysicsTestAccess.javaFactory(false);

    @Test
    void freeFallMatchesVanillaArithmetic() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 20.0, 0.5, 0, 0, 0, false, 0);
            double y = 20.0, vy = 0.0;
            for (int tick = 0; tick < 10; tick++) {
                assertEquals(0, b.step(t, 0));
                vy = vy + -0.04;         // applyGravity: getDeltaMovement().add(0, -0.04, 0)
                y = y + vy;              // move: no collision in the air
                vy = vy * 0.98;          // multiply(f, 0.98, f)
                assertEquals(y, b.y(0), "tick " + tick);
                assertEquals(vy, b.vy(0), "tick " + tick);
            }
            assertEquals(0, b.flags(0) & BodyFlags.ON_GROUND);
        }
    }

    @Test
    void landsOnFloorAndRests() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 8.0, 0.5, 0, 0, 0, false, 0);
            for (int tick = 0; tick < 60; tick++) {
                b.step(t, 0);
            }
            assertEquals(5.0, b.y(0), "rests exactly on the floor top");
            assertNotEquals(0, b.flags(0) & BodyFlags.ON_GROUND);
            assertEquals(0.0, b.vx(0));
            assertEquals(0.0, b.vz(0));
        }
    }

    @Test
    void restingItemMovesOnlyEveryFourthTick() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 5.0, 0.5, 0, 0, 0, true, 1);
            int held = 0;
            for (int tick = 0; tick < 8; tick++) {
                b.step(t, 0);
                held += (b.flags(0) & BodyFlags.HELD) != 0 ? 1 : 0;
            }
            assertEquals(6, held, "phase != 0 on 3 of every 4 ticks");
            assertEquals(5.0, b.y(0));
        }
    }

    @Test
    void wallStopsHorizontalMotion() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 5.7, 5.0, 0.5, 0.3, 0, 0, true, 0);
            b.step(t, 0);
            assertEquals(6.0 - 0.125, b.x(0), "box face stops at the wall face x = 6");
            assertEquals(0.0, b.vx(0));
            assertNotEquals(0, b.flags(0) & BodyFlags.H_COLLISION);
        }
    }

    @Test
    void iceIsSlipperier() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 3.5, 5.0, 3.5, 0.2, 0, 0, true, 0);
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 5.0, 10.5, 0.2, 0, 0, true, 0);
            b.step(t, 0);
            assertEquals(0.2 * (double) (0.98F * 0.98F), b.vx(0), "ice: friction 0.98F * 0.98F");
            assertEquals(0.2 * (double) (0.6F * 0.98F), b.vx(1), "stone: friction 0.6F * 0.98F");
        }
    }

    @Test
    void complexCellsAndUnloadedSectionsDefer() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory);
             BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 9.5, 5.0, 9.5, 0, 0, 0, true, 0);   // touches the complex cell
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 100.5, 5.0, 0.5, 0, 0, 0, true, 0); // outside the window
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 4.5, 0.5, 0, 0, 0, true, 0);   // stuck inside the floor
            b.add(BodyParams.ITEM, 0.25F, 0.25F, 0.5, 5.0, 0.5, 0, 0, 0, true, 0);   // fine
            double y3 = b.y(3);
            assertEquals(3, b.step(t, 0));
            assertEquals(0, b.deferred(0));
            assertEquals(1, b.deferred(1));
            assertEquals(2, b.deferred(2));
            assertEquals(0, b.flags(3) & BodyFlags.DEFERRED);
            assertEquals(y3, b.y(3));
            assertEquals(9.5, b.x(0), "deferred bodies keep their position");
        }
    }

    @Test
    void removeSwapsLastIntoHole() {
        try (BodyBatch b = factory.newBatch(16, PhysicsMode.VANILLA, 0)) {
            for (int i = 0; i < 5; i++) {
                b.add(BodyParams.ITEM, 0.25F, 0.25F, i, 0, 0, 0, 0, 0, false, 0);
            }
            assertEquals(4, b.remove(1));
            assertEquals(4.0, b.x(1));
            assertEquals(-1, b.remove(3));
            assertEquals(3, b.size());
        }
    }

    @Test
    void broadphaseMatchesBruteForce() {
        try (BodyBatch b = factory.newBatch(2000, PhysicsMode.VANILLA, 100_000)) {
            java.util.SplittableRandom r = new java.util.SplittableRandom(7);
            for (int i = 0; i < 2000; i++) {
                b.add(BodyParams.ITEM, (float) (0.2 + r.nextDouble() * 0.6), (float) (0.2 + r.nextDouble()),
                        r.nextDouble() * 30, r.nextDouble() * 8, r.nextDouble() * 30, 0, 0, 0, false, 0);
            }
            double m = 0.5;
            int pairs = b.broadphase(m);
            double[] hw = column(b, com.panzer.mods.celeris.physics.BodyLayout.HALF_WIDTH);
            double[] h = column(b, com.panzer.mods.celeris.physics.BodyLayout.HEIGHT);
            int brute = 0;
            for (int i = 0; i < b.size(); i++) {
                for (int j = i + 1; j < b.size(); j++) {
                    if (overlap(b, hw, h, i, j, m)) {
                        brute++;
                    }
                }
            }
            assertEquals(brute, pairs);
            for (int k = 0; k < pairs; k++) {
                assertTrue(b.pairA(k) < b.pairB(k));
                assertTrue(overlap(b, hw, h, b.pairA(k), b.pairB(k), m));
            }
        }
    }

    private static boolean overlap(BodyBatch b, double[] hw, double[] h, int i, int j, double m) {
        return b.x(i) - hw[i] - m < b.x(j) + hw[j] && b.x(i) + hw[i] + m > b.x(j) - hw[j]
                && b.y(i) - m < b.y(j) + h[j] && b.y(i) + h[i] + m > b.y(j)
                && b.z(i) - hw[i] - m < b.z(j) + hw[j] && b.z(i) + hw[i] + m > b.z(j) - hw[j];
    }

    /** Reads a column through the raw slab layout, keeping test-only getters out of the API. */
    private static double[] column(BodyBatch b, int column) {
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.wrap(PhysicsTestAccess.snapshot(b)).order(java.nio.ByteOrder.nativeOrder());
        double[] out = new double[b.size()];
        long base = com.panzer.mods.celeris.physics.BodyLayout.f64Offset(column, b.capacity());
        for (int i = 0; i < out.length; i++) {
            out[i] = raw.getDouble((int) (base + 8L * i));
        }
        return out;
    }
}
