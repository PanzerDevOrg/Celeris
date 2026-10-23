import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.CellClass;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsJniTestAccess;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The JNI engine's own behaviour: terrain, bounds and lifetime. Kernel results are in {@link PhysicsParityTest}. */
class PhysicsJniTest {

    private static PhysicsFactory jniOrSkip() {
        try {
            return PhysicsJniTestAccess.jniFactory();
        } catch (Throwable t) {
            assumeTrue(false, "celeris_physics not built for this platform (" + t + ")");
            return null;
        }
    }

    @Test
    void engineNameSaysJni() {
        PhysicsFactory jni = jniOrSkip();
        assertTrue(jni.engineName().startsWith("native-jni-"), jni.engineName());
    }

    @Test
    void terrainBehavesLikeTheReferenceTerrain() {
        PhysicsFactory jni = jniOrSkip();
        try (TerrainView a = PhysicsTestSupport.randomWorld(com.panzer.mods.celeris.physics.PhysicsTestAccess.javaFactory(), 7);
             TerrainView b = PhysicsTestSupport.randomWorld(jni, 7)) {
            for (int x = -20; x < 36; x += 3) {
                for (int y = -2; y < 36; y += 3) {
                    for (int z = -20; z < 36; z += 3) {
                        assertEquals(a.cell(x, y, z), b.cell(x, y, z), x + "," + y + "," + z);
                    }
                }
            }
            assertEquals(a.solidClass(0.91F), b.solidClass(0.91F));
            assertEquals(a.friction(a.solidClass(0.91F)), b.friction(b.solidClass(0.91F)));
            b.clearSection(0, 0, 0);
            assertEquals(CellClass.COMPLEX, b.cell(1, 1, 1));
            // A page grown past the initial pool keeps its contents.
            TerrainView big = jni.newTerrain(16, 2, 16);
            try {
                for (int sx = 0; sx < 16; sx++) {
                    for (int sz = 0; sz < 16; sz++) {
                        big.setCell(sx << 4, 0, sz << 4, CellClass.SOLID_DEFAULT);
                    }
                }
                for (int sx = 0; sx < 16; sx++) {
                    for (int sz = 0; sz < 16; sz++) {
                        assertEquals(CellClass.SOLID_DEFAULT, big.cell(sx << 4, 0, sz << 4));
                    }
                }
            } finally {
                big.close();
            }
        }
    }

    @Test
    void hostileSlotsAndClosedBatchesThrowInsteadOfTouchingMemory() {
        PhysicsFactory jni = jniOrSkip();
        BodyBatch batch = jni.newBatch(64, PhysicsMode.VANILLA, 16);
        batch.add(BodyParams.ITEM, 0.25F, 0.25F, 0, 5, 0, 0, 0, 0, false, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> batch.x(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.x(batch.capacity()));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.setPosition(Integer.MAX_VALUE, 0, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.remove(1));
        assertThrows(IndexOutOfBoundsException.class, () -> batch.pairA(16));
        batch.close();
        batch.close(); // idempotent
        assertThrows(IndexOutOfBoundsException.class, () -> batch.x(0));
        try (TerrainView t = PhysicsTestSupport.emptyWorld(jni)) {
            assertThrows(IllegalStateException.class, () -> batch.step(t, 0));
            assertThrows(IllegalStateException.class, () -> batch.broadphase(0.5));
        }
    }

    @Test
    void closedTerrainIsRejectedByStep() {
        PhysicsFactory jni = jniOrSkip();
        TerrainView t = PhysicsTestSupport.emptyWorld(jni);
        t.close();
        try (BodyBatch batch = jni.newBatch(16, PhysicsMode.VANILLA, 0)) {
            assertThrows(IllegalStateException.class, () -> batch.step(t, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> jni.newTerrain(0, 1, 1));
    }
}
