import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainQuery;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TerrainQuery} on the floor scene: stone top at y = 5, a wall cube at (6, 5, 0), a complex cell at (9, 5, 9). */
class TerrainQueryTest {

    private final PhysicsFactory factory = PhysicsTestAccess.javaFactory();

    @Test
    void supportIsTheNearestCubeBelow() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory)) {
            int[] out = new int[3];
            // An item resting on the floor: its box grown down by 1e-6, as Entity.checkSupportingBlock does.
            assertEquals(TerrainQuery.FOUND, TerrainQuery.support(t, 0.375, 5.0 - 1.0E-6, 0.375, 0.625, 5.25, 0.625,
                    0.5, 5.0, 0.5, out));
            assertArrayEquals(new int[] {0, 4, 0}, out);
            assertEquals(TerrainQuery.NONE, TerrainQuery.support(t, 0.375, 10.0, 0.375, 0.625, 10.25, 0.625,
                    0.5, 10.0, 0.5, out));
            assertEquals(TerrainQuery.UNKNOWN, TerrainQuery.support(t, 9.375, 5.0 - 1.0E-6, 9.375, 9.625, 5.25, 9.625,
                    9.5, 5.0, 9.5, out));
        }
    }

    @Test
    void modelledStopsAtComplexAndUnloadedCells() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory)) {
            assertTrue(TerrainQuery.modelled(t, 0.375, 5.0, 0.375, 0.625, 5.25, 0.625));
            assertTrue(TerrainQuery.modelled(t, 5.5, 4.5, 0.2, 6.5, 5.5, 0.8), "air, floor and a wall cube");
            assertFalse(TerrainQuery.modelled(t, 9.375, 5.0, 9.375, 9.625, 5.25, 9.625));
            // Closed box: a face exactly on x = 9 touches the complex cell.
            assertFalse(TerrainQuery.modelled(t, 8.5, 5.0, 9.375, 9.0, 5.25, 9.625));
            assertFalse(TerrainQuery.modelled(t, 0.375, 40.0, 0.375, 0.625, 40.25, 0.625), "outside the snapshot");
        }
    }
}
