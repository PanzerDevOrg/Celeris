package com.panzer.mods.celeris.physics;

/**
 * Questions a game would otherwise ask its level, answered from a
 * {@link TerrainView} with a few byte reads. When the answer depends on a cell
 * the snapshot does not model ({@link CellClass#COMPLEX}: other shapes, fluids,
 * unloaded sections) the caller asks the level instead.
 */
public final class TerrainQuery {

    public static final int NONE = 0;
    public static final int FOUND = 1;
    public static final int UNKNOWN = -1;

    private TerrainQuery() {
    }

    /**
     * {@code Level.findSupportingBlock}: the full cube strictly intersecting
     * {@code [bx0, bx1] x [by0, by1] x [bz0, bz1]} whose centre is nearest the
     * entity position {@code (px, py, pz)}, ties to the greatest position (y,
     * then z, then x: Vec3i.compareTo) -- the same search, in the same order, as
     * the kernels' supporting-block step. On {@link #FOUND} its block position
     * is in {@code out[0..2]}; {@link #UNKNOWN} when the box touches a
     * {@link CellClass#COMPLEX} cell.
     */
    public static int support(TerrainView terrain, double bx0, double by0, double bz0, double bx1, double by1,
                              double bz1, double px, double py, double pz, int[] out) {
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

    /**
     * Whether every block touching the closed box {@code [x0, x1] x [y0, y1] x
     * [z0, z1]} (cells {@code floor(min)} to {@code floor(max)}, as
     * {@code BlockPos.betweenClosed}) is modelled: air or a solid class, which a
     * game maps only to blocks with no effect on an entity inside them. It can
     * then skip its per-block "entity inside" walk over that box.
     */
    public static boolean modelled(TerrainView terrain, double x0, double y0, double z0, double x1, double y1,
                                   double z1) {
        int cx1 = JavaPhysicsKernel.ifloor(x1), cy1 = JavaPhysicsKernel.ifloor(y1), cz1 = JavaPhysicsKernel.ifloor(z1);
        for (int z = JavaPhysicsKernel.ifloor(z0); z <= cz1; z++) {
            for (int y = JavaPhysicsKernel.ifloor(y0); y <= cy1; y++) {
                for (int x = JavaPhysicsKernel.ifloor(x0); x <= cx1; x++) {
                    if (terrain.cell(x, y, z) == CellClass.COMPLEX) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
