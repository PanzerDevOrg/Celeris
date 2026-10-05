package com.panzer.mods.celeris.physics;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * {@link TerrainView} on off-heap memory: an int32 section directory, a
 * growable pool of 4 KiB pages, a 256-entry float friction table, and a
 * {@code cp_terrain} header struct pointing at all three, which is what the
 * native kernel receives.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class SegmentTerrain implements TerrainView {

    /** Mirrors {@code struct cp_terrain}; natural alignment, 48 bytes. */
    static final StructLayout HEADER = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("directory"),
            ValueLayout.ADDRESS.withName("pages"),
            ValueLayout.ADDRESS.withName("friction"),
            ValueLayout.JAVA_INT.withName("origin_x"),
            ValueLayout.JAVA_INT.withName("origin_y"),
            ValueLayout.JAVA_INT.withName("origin_z"),
            ValueLayout.JAVA_INT.withName("size_x"),
            ValueLayout.JAVA_INT.withName("size_y"),
            ValueLayout.JAVA_INT.withName("size_z"));

    private static final long H_PAGES = HEADER.byteOffset(groupElement("pages"));
    private static final long H_ORIGIN_X = HEADER.byteOffset(groupElement("origin_x"));

    private static final int PAGE = SECTION_BYTES;
    private static final float DEFAULT_FRICTION = 0.6F;

    private final Arena arena = Arena.ofShared();
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    final MemorySegment header;
    final MemorySegment directory;
    final MemorySegment friction;
    MemorySegment pages;
    private Arena pagesArena;
    private int pageCapacity;
    private int[] freePages;
    private int freeCount;
    private int originX;
    private int originY;
    private int originZ;
    private int frictionClasses = 1;

    SegmentTerrain(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > (1 << 24)) {
            throw new IllegalArgumentException("terrain window out of range: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int sections = sizeX * sizeY * sizeZ;
        header = arena.allocate(HEADER);
        directory = arena.allocate((long) sections * Integer.BYTES, 64);
        directory.fill((byte) 0xFF); // every entry -1: unloaded
        friction = arena.allocate(256L * Float.BYTES, 64);
        for (int c = 0; c < 256; c++) {
            friction.setAtIndex(ValueLayout.JAVA_FLOAT, c, DEFAULT_FRICTION);
        }
        header.set(ValueLayout.ADDRESS, HEADER.byteOffset(groupElement("directory")), directory);
        header.set(ValueLayout.ADDRESS, HEADER.byteOffset(groupElement("friction")), friction);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_x")), sizeX);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_y")), sizeY);
        header.set(ValueLayout.JAVA_INT, HEADER.byteOffset(groupElement("size_z")), sizeZ);
        growPages(Math.min(sections, 64));
        writeOrigin();
    }

    @Override
    public int sizeX() {
        return sizeX;
    }

    @Override
    public int sizeY() {
        return sizeY;
    }

    @Override
    public int sizeZ() {
        return sizeZ;
    }

    @Override
    public int originX() {
        return originX;
    }

    @Override
    public int originY() {
        return originY;
    }

    @Override
    public int originZ() {
        return originZ;
    }

    @Override
    public void setOrigin(int sectionX, int sectionY, int sectionZ) {
        originX = sectionX;
        originY = sectionY;
        originZ = sectionZ;
        directory.fill((byte) 0xFF);
        freeCount = 0;
        for (int p = pageCapacity - 1; p >= 0; p--) {
            freePages[freeCount++] = p;
        }
        writeOrigin();
    }

    @Override
    public boolean covers(int sectionX, int sectionY, int sectionZ) {
        return dirIndex(sectionX, sectionY, sectionZ) >= 0;
    }

    @Override
    public void setSection(int sectionX, int sectionY, int sectionZ, byte[] cells, int offset) {
        int page = pageFor(sectionX, sectionY, sectionZ, false);
        if (page >= 0) {
            MemorySegment.copy(cells, offset, pages, ValueLayout.JAVA_BYTE, (long) page * PAGE, PAGE);
        }
    }

    @Override
    public void clearSection(int sectionX, int sectionY, int sectionZ) {
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return;
        }
        int page = directory.getAtIndex(ValueLayout.JAVA_INT, d);
        if (page >= 0) {
            directory.setAtIndex(ValueLayout.JAVA_INT, d, -1);
            freePages[freeCount++] = page;
        }
    }

    @Override
    public void setCell(int x, int y, int z, int cellClass) {
        int page = pageFor(x >> 4, y >> 4, z >> 4, true);
        if (page >= 0) {
            pages.set(ValueLayout.JAVA_BYTE, (long) page * PAGE + local(x, y, z), (byte) cellClass);
        }
    }

    @Override
    public int cell(int x, int y, int z) {
        int d = dirIndex(x >> 4, y >> 4, z >> 4);
        if (d < 0) {
            return CellClass.COMPLEX;
        }
        int page = directory.getAtIndex(ValueLayout.JAVA_INT, d);
        if (page < 0) {
            return CellClass.COMPLEX;
        }
        return pages.get(ValueLayout.JAVA_BYTE, (long) page * PAGE + local(x, y, z)) & 0xFF;
    }

    @Override
    public int solidClass(float value) {
        if (value == DEFAULT_FRICTION) {
            return CellClass.SOLID_DEFAULT;
        }
        for (int c = 2; c <= frictionClasses; c++) {
            if (Float.floatToRawIntBits(friction(c)) == Float.floatToRawIntBits(value)) {
                return c;
            }
        }
        if (frictionClasses >= CellClass.LAST_SOLID) {
            throw new IllegalStateException("more than 253 distinct block friction values");
        }
        int c = ++frictionClasses;
        friction.setAtIndex(ValueLayout.JAVA_FLOAT, c, value);
        return c;
    }

    @Override
    public float friction(int cellClass) {
        return friction.getAtIndex(ValueLayout.JAVA_FLOAT, cellClass & 0xFF);
    }

    /** Directory index of a section, or -1 outside the window. */
    int dirIndex(int sectionX, int sectionY, int sectionZ) {
        int sx = sectionX - originX;
        int sy = sectionY - originY;
        int sz = sectionZ - originZ;
        if (Integer.compareUnsigned(sx, sizeX) >= 0 | Integer.compareUnsigned(sy, sizeY) >= 0
                | Integer.compareUnsigned(sz, sizeZ) >= 0) {
            return -1;
        }
        return (sy * sizeZ + sz) * sizeX + sx;
    }

    static int local(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private int pageFor(int sectionX, int sectionY, int sectionZ, boolean fillComplex) {
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return -1;
        }
        int page = directory.getAtIndex(ValueLayout.JAVA_INT, d);
        if (page >= 0) {
            return page;
        }
        if (freeCount == 0) {
            growPages(pageCapacity * 2);
        }
        page = freePages[--freeCount];
        if (fillComplex) {
            pages.asSlice((long) page * PAGE, PAGE).fill((byte) CellClass.COMPLEX);
        }
        directory.setAtIndex(ValueLayout.JAVA_INT, d, page);
        return page;
    }

    private void growPages(int newCapacity) {
        Arena next = Arena.ofShared();
        // +64: the kernels never read past a page, but keep a cache line of slack anyway.
        MemorySegment grown = next.allocate((long) newCapacity * PAGE + 64, 64);
        if (pages != null) {
            MemorySegment.copy(pages, 0, grown, 0, (long) pageCapacity * PAGE);
            pagesArena.close();
        }
        int[] free = freePages == null ? new int[newCapacity] : Arrays.copyOf(freePages, newCapacity);
        for (int p = newCapacity - 1; p >= pageCapacity; p--) {
            free[freeCount++] = p;
        }
        freePages = free;
        pages = grown;
        pagesArena = next;
        pageCapacity = newCapacity;
        header.set(ValueLayout.ADDRESS, H_PAGES, pages);
    }

    private void writeOrigin() {
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X, originX);
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X + 4, originY);
        header.set(ValueLayout.JAVA_INT, H_ORIGIN_X + 8, originZ);
    }

    @Override
    public void close() {
        pagesArena.close();
        arena.close();
    }
}
