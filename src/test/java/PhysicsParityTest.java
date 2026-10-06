import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native kernel must produce exactly what the pure-Java reference engine
 * produces, on every ISA this CPU supports: positions, velocities, flags and
 * deferred lists compared bit for bit after every tick. Randomised scenes
 * cover landings, walls, ledges, throttled items, friction classes and
 * deferral; 60 ticks let state (onGround, phase, onGroundNoBlocks) feed back
 * into later ticks.
 */
class PhysicsParityTest {

    private static final int BODIES = 3000;
    private static final int TICKS = 60;
    private static final int[] RULES = {0, PhysicsMode.RULE_SMALL_MOVES};

    private static PhysicsFactory nativeOrSkip() {
        try {
            return PhysicsTestAccess.nativeFactory();
        } catch (Throwable t) {
            assumeTrue(false, "celeris_physics not built for this platform (" + t.getMessage()
                    + "); run ./gradlew buildNativeCelerisPhysics");
            return null;
        }
    }

    @Test
    void nativeMatchesReferenceOnEveryIsa() {
        PhysicsFactory nat = nativeOrSkip();
        PhysicsFactory ref = PhysicsTestAccess.javaFactory();
        for (String isa : PhysicsTestAccess.nativeIsas(nat)) {
            assertEquals(isa, PhysicsTestAccess.forceNativeIsa(nat, isa));
            for (PhysicsMode mode : PhysicsMode.values()) {
                for (int rules : RULES) {
                    assertSameRun(ref, nat, mode, rules, "native-" + isa);
                }
            }
        }
    }

    @Test
    void nativeBroadphaseMatchesReferenceOrder() {
        PhysicsFactory nat = nativeOrSkip();
        PhysicsFactory ref = PhysicsTestAccess.javaFactory();
        try (BodyBatch a = ref.newBatch(BODIES, PhysicsMode.VANILLA, 50_000);
             BodyBatch b = nat.newBatch(BODIES, PhysicsMode.VANILLA, 50_000)) {
            PhysicsTestSupport.randomBodies(a, BODIES, 3);
            PhysicsTestSupport.randomBodies(b, BODIES, 3);
            int na = a.broadphase(0.5);
            assertEquals(na, b.broadphase(0.5));
            assertTrue(na > 0);
            for (int k = 0; k < Math.min(na, 50_000); k++) {
                assertEquals(a.pairA(k), b.pairA(k), "pair " + k);
                assertEquals(a.pairB(k), b.pairB(k), "pair " + k);
            }
        }
    }

    private static void assertSameRun(PhysicsFactory ref, PhysicsFactory other, PhysicsMode mode, int rules, String label) {
        try (TerrainView ta = PhysicsTestSupport.randomWorld(ref, 11);
             TerrainView tb = PhysicsTestSupport.randomWorld(other, 11);
             BodyBatch a = ref.newBatch(BODIES, mode, 0);
             BodyBatch b = other.newBatch(BODIES, mode, 0)) {
            PhysicsTestSupport.randomBodies(a, BODIES, 5);
            PhysicsTestSupport.randomBodies(b, BODIES, 5);
            int deferred = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                String at = label + " " + mode + " rules " + rules + " tick " + tick;
                int da = a.step(ta, rules);
                assertEquals(da, b.step(tb, rules), at + ": deferred count");
                for (int k = 0; k < da; k++) {
                    assertEquals(a.deferred(k), b.deferred(k), at + ": deferred[" + k + "]");
                }
                for (int i = 0; i < BODIES; i++) {
                    if (!sameBits(a.x(i), b.x(i)) || !sameBits(a.y(i), b.y(i)) || !sameBits(a.z(i), b.z(i))
                            || !sameBits(a.vx(i), b.vx(i)) || !sameBits(a.vy(i), b.vy(i)) || !sameBits(a.vz(i), b.vz(i))
                            || a.flags(i) != b.flags(i)) {
                        fail(at + ": body " + i + " differs: java (" + a.x(i) + ", " + a.y(i) + ", " + a.z(i) + ") v("
                                + a.vx(i) + ", " + a.vy(i) + ", " + a.vz(i) + ") flags " + Integer.toHexString(a.flags(i))
                                + " vs " + label + " (" + b.x(i) + ", " + b.y(i) + ", " + b.z(i) + ") v("
                                + b.vx(i) + ", " + b.vy(i) + ", " + b.vz(i) + ") flags " + Integer.toHexString(b.flags(i)));
                    }
                }
                deferred += da;
            }
            assertTrue(deferred > 0 && deferred < TICKS * BODIES, "scene exercises both paths");
        }
    }

    private static boolean sameBits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
}
