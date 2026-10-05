package com.panzer.mods.celeris.physics;

/**
 * One byte per block in a {@link TerrainView} (mirrors {@code CP_CELL_*}).
 *
 * <p>Only two kinds of block are simulated exactly: {@link #AIR} and full
 * solid cubes ({@code 1..254}, the value indexing the friction table). Every
 * other block -- partial shapes, fluids, anything with {@code entityInside},
 * {@code stepOn}, {@code fallOn}, a speed factor or a bounce -- is {@link
 * #COMPLEX}: a body whose swept box touches one is deferred to the vanilla
 * code path for that tick. Blocks whose shape is taller than their cell
 * (fences, walls) must also mark their neighbours {@code COMPLEX}.
 */
public final class CellClass {

    public static final int AIR = 0x00;
    public static final int COMPLEX = 0xFF;
    /** Full cube with the default friction 0.6. */
    public static final int SOLID_DEFAULT = 0x01;
    public static final int FIRST_SOLID = 0x01;
    public static final int LAST_SOLID = 0xFE;

    private CellClass() {
    }

    public static boolean isSolid(int cell) {
        int c = cell & 0xFF;
        return c != AIR && c != COMPLEX;
    }
}
