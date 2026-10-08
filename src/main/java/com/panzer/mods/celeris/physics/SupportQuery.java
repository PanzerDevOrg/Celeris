package com.panzer.mods.celeris.physics;

/**
 * {@code Level.findSupportingBlock} answered from a {@link TerrainView}: the full
 * cube strictly intersecting the box whose centre is nearest the entity
 * position, ties to the greatest position (y, then z, then x: Vec3i.compareTo)
 * -- the same search, in the same order, as the kernels' supporting-block step.
 * A few byte reads instead of the level's collision iterator. When the box
 * touches a cell the snapshot does not model ({@link CellClass#COMPLEX}: other
 * shapes, unloaded sections) the answer is {@link #UNKNOWN} and the caller asks
 * the level.
 */
public final class SupportQuery {

    public static final int NONE = 0;
    public static final int FOUND = 1;
    public static final int UNKNOWN = -1;

    private SupportQuery() {
    }

    /**
     * Searches {@code [bx0, bx1] x [by0, by1] x [bz0, bz1]} for the supporting
     * block of an entity at {@code (px, py, pz)}; on {@link #FOUND} its block
     * position is in {@code out[0..2]}.
     */
    public static int find(TerrainView terrain, double bx0, double by0, double bz0, double bx1, double by1, double bz1,
                           double px, double py, double pz, int[] out) {
        int x0 = JavaPhysicsKernel.ifloor(bx0), x1 = JavaPhysicsKernel.iceil(bx1) - 1;
        int y0 = JavaPhysicsKernel.ifloor(by0), y1 = JavaPhysicsKernel.iceil(by1) - 1;
        int z0 = JavaPhysicsKernel.ifloor(bz0), z1 = JavaPhysicsKernel.iceil(bz1) - 1;
        boolean found = false;
        double best = 0.0;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int c = terrain.cell(x, y, z);
                    if (c == CellClass.COMPLEX) {
                        return UNKNOWN;
                    }
                    if (!JavaPhysicsKernel.solid(c)) {
                        continue;
                    }
                    double dx = (double) x + 0.5 - px;
                    double dy = (double) y + 0.5 - py;
                    double dz = (double) z + 0.5 - pz;
                    double dist = dx * dx + dy * dy + dz * dz;
                    boolean greater = y != out[1] ? y > out[1] : (z != out[2] ? z > out[2] : x > out[0]);
                    if (!found || dist < best || (dist == best && greater)) {
                        found = true;
                        best = dist;
                        out[0] = x;
                        out[1] = y;
                        out[2] = z;
                    }
                }
            }
        }
        return found ? FOUND : NONE;
    }
}
