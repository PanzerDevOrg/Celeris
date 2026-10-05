import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every kernel must produce the same bytes as the Java reference: the native
 * library on every ISA this CPU supports, and the Vector API streaming
 * passes. Randomised scenes cover landings, walls, ledges, throttled items,
 * friction classes and deferral; 60 ticks let state (onGround, phase,
 * onGroundNoBlocks) feed back into later ticks.
 */
class PhysicsParityTest {

    private static final int BODIES = 3000;
    private static final int TICKS = 60;
    private static final int[] RULES = {0, PhysicsMode.RULE_SMALL_MOVES};

    @Test
    void nativeMatchesReferenceOnEveryIsa() {
        PhysicsFactory nat;
        try {
            nat = PhysicsTestAccess.nativeFactory();
        } catch (Throwable t) {
            assumeTrue(false, "celeris_physics not built for this platform (" + t.getMessage()
                    + "); run ./gradlew buildPhysicsNative");
            return;
        }
        PhysicsFactory ref = PhysicsTestAccess.javaFactory(false);
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
    void vectorStreamsMatchReference() {
        PhysicsFactory simd;
        try {
            simd = PhysicsTestAccess.javaFactory(true);
        } catch (Throwable t) {
            assumeTrue(false, "jdk.incubator.vector not enabled on the test JVM");
            return;
        }
        PhysicsFactory ref = PhysicsTestAccess.javaFactory(false);
        for (PhysicsMode mode : PhysicsMode.values()) {
            assertSameRun(ref, simd, mode, 0, simd.engineName());
        }
    }

    @Test
    void nativeBroadphaseMatchesReferenceOrder() {
        PhysicsFactory nat;
        try {
            nat = PhysicsTestAccess.nativeFactory();
        } catch (Throwable t) {
            assumeTrue(false, "celeris_physics not built for this platform");
            return;
        }
        PhysicsFactory ref = PhysicsTestAccess.javaFactory(false);
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
                int da = a.step(ta, rules);
                int db = b.step(tb, rules);
                assertEquals(da, db, label + " " + mode + " tick " + tick + ": deferred count");
                for (int k = 0; k < da; k++) {
                    assertEquals(a.deferred(k), b.deferred(k), label + " deferred[" + k + "]");
                }
                deferred += da;
            }
            byte[] sa = PhysicsTestAccess.snapshot(a);
            byte[] sb = PhysicsTestAccess.snapshot(b);
            // Scratch columns after FLAGS (deferred list, broadphase) may differ in stale bytes.
            int compared = (int) com.panzer.mods.celeris.physics.BodyLayout.u32Offset(
                    com.panzer.mods.celeris.physics.BodyLayout.DEFERRED_LIST, a.capacity());
            for (int i = 0; i < compared; i++) {
                if (sa[i] != sb[i]) {
                    fail(label + " " + mode + " rules " + rules + ": slab differs from reference at byte " + i);
                }
            }
            assertTrue(deferred > 0 && deferred < TICKS * BODIES, "scene exercises both paths");
        }
    }
}
