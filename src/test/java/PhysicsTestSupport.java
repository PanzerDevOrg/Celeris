import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.CellClass;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.TerrainView;

import java.util.SplittableRandom;

/** Shared scenes for the physics tests. */
final class PhysicsTestSupport {

    static final float ITEM_SIZE = 0.25F;

    private PhysicsTestSupport() {
    }

    /** 3x2x3 sections around the origin (x, z in [-16, 32), y in [0, 32)), all air. */
    static TerrainView emptyWorld(PhysicsFactory factory) {
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
        return t;
    }

    /** Stone floor with its top at y = 5, one ice block, one wall cube, one complex cell. */
    static TerrainView floorWorld(PhysicsFactory factory) {
        TerrainView t = emptyWorld(factory);
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                t.setCell(x, 4, z, CellClass.SOLID_DEFAULT);
            }
        }
        t.setCell(3, 4, 3, t.solidClass(0.98F));
        t.setCell(6, 5, 0, CellClass.SOLID_DEFAULT);
        t.setCell(9, 5, 9, CellClass.COMPLEX);
        return t;
    }

    /** Random terrain: solid floor below y = 3, scattered stone/ice/complex cells above. */
    static TerrainView randomWorld(PhysicsFactory factory, long seed) {
        TerrainView t = emptyWorld(factory);
        SplittableRandom r = new SplittableRandom(seed);
        int ice = t.solidClass(0.98F);
        int slime = t.solidClass(0.8F);
        for (int x = -16; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = -16; z < 32; z++) {
                    double v = r.nextDouble();
                    if (y < 3 || v < 0.15) {
                        t.setCell(x, y, z, v < 0.03 ? ice : v < 0.05 ? slime : CellClass.SOLID_DEFAULT);
                    } else if (v < 0.17) {
                        t.setCell(x, y, z, CellClass.COMPLEX);
                    }
                }
            }
        }
        return t;
    }

    /** Random items in the random world, some resting, some wide. */
    static void randomBodies(BodyBatch batch, int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 0; i < n; i++) {
            float w = r.nextDouble() < 0.3 ? 0.6F : ITEM_SIZE;
            batch.add(BodyParams.ITEM, w, r.nextDouble() < 0.2 ? 1.8F : ITEM_SIZE,
                    -14 + r.nextDouble() * 44, 3 + r.nextDouble() * 26, -14 + r.nextDouble() * 44,
                    (r.nextDouble() - 0.5) * 0.8, (r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 0.8,
                    r.nextBoolean(), i & 3);
        }
    }
}
