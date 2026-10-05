package com.panzer.mods.celeris.physics;

/**
 * Off-heap voxel snapshot the kernels collide against: a dense window of
 * {@code sizeX * sizeY * sizeZ} chunk sections, each either unloaded (every
 * cell {@link CellClass#COMPLEX}) or backed by a 4 KiB page of {@link
 * CellClass} bytes, index {@code (y << 8) | (z << 4) | x}. Pages come from a
 * pool, so only sections that hold or border bodies cost memory.
 *
 * <p>Fill it from the level once (whole sections, bulk-copied), then keep it
 * current from block-change events with {@link #setCell}. Must not be
 * modified while a {@link BodyBatch#step} that reads it is running.
 */
public interface TerrainView extends AutoCloseable {

    int SECTION_BYTES = 4096;

    int sizeX();

    int sizeY();

    int sizeZ();

    int originX();

    int originY();

    int originZ();

    /** Moves the window (in section coordinates). Drops every page. */
    void setOrigin(int sectionX, int sectionY, int sectionZ);

    /** Whether the section is inside the window. */
    boolean covers(int sectionX, int sectionY, int sectionZ);

    /** Copies 4096 cell bytes ({@code (y << 8) | (z << 4) | x}) into the section's page. */
    void setSection(int sectionX, int sectionY, int sectionZ, byte[] cells, int offset);

    /** Marks a section unloaded: every cell reads as {@link CellClass#COMPLEX}, its page returns to the pool. */
    void clearSection(int sectionX, int sectionY, int sectionZ);

    /** Single block update. A cell in an unloaded section allocates its page, filled with COMPLEX. */
    void setCell(int x, int y, int z, int cellClass);

    int cell(int x, int y, int z);

    /**
     * Returns the solid cell class for a block friction value, registering it
     * on first use (at most 254 distinct values; 0.6 is always
     * {@link CellClass#SOLID_DEFAULT}).
     */
    int solidClass(float friction);

    float friction(int cellClass);

    @Override
    void close();
}
