package com.panzer.mods.celeris.physics;

import java.util.Arrays;

/**
 * {@link TerrainView} on Java arrays, for the pure-Java engine (no FFM, no
 * JVM flags). Same model as the off-heap view: an int section directory, a
 * pool of 4 KiB pages in one growable {@code byte[]}, a 256-entry float
 * friction table.
 */
final class HeapTerrain implements TerrainView {

    private static final int PAGE = SECTION_BYTES;
    private static final float DEFAULT_FRICTION = 0.6F;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    final int[] directory;
    final float[] friction = new float[256];
    byte[] pages;
    private int pageCapacity;
    private int[] freePages = new int[0];
    private int freeCount;
    int originX;
    int originY;
    int originZ;
    private int frictionClasses = 1;

    HeapTerrain(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > (1 << 24)) {
            throw new IllegalArgumentException("terrain window out of range: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int sections = sizeX * sizeY * sizeZ;
        directory = new int[sections];
        Arrays.fill(directory, -1);
        Arrays.fill(friction, DEFAULT_FRICTION);
        pages = new byte[0];
        growPages(Math.min(sections, 64));
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
        Arrays.fill(directory, -1);
        freeCount = 0;
        for (int p = pageCapacity - 1; p >= 0; p--) {
            freePages[freeCount++] = p;
        }
    }

    @Override
    public boolean covers(int sectionX, int sectionY, int sectionZ) {
        return dirIndex(sectionX, sectionY, sectionZ) >= 0;
    }

    @Override
    public void setSection(int sectionX, int sectionY, int sectionZ, byte[] cells, int offset) {
        int page = pageFor(sectionX, sectionY, sectionZ, false);
        if (page >= 0) {
            System.arraycopy(cells, offset, pages, page * PAGE, PAGE);
        }
    }

    @Override
    public void clearSection(int sectionX, int sectionY, int sectionZ) {
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d >= 0 && directory[d] >= 0) {
            freePages[freeCount++] = directory[d];
            directory[d] = -1;
        }
    }

    @Override
    public void setCell(int x, int y, int z, int cellClass) {
        int page = pageFor(x >> 4, y >> 4, z >> 4, true);
        if (page >= 0) {
            pages[page * PAGE + local(x, y, z)] = (byte) cellClass;
        }
    }

    @Override
    public int cell(int x, int y, int z) {
        int d = dirIndex(x >> 4, y >> 4, z >> 4);
        if (d < 0 || directory[d] < 0) {
            return CellClass.COMPLEX;
        }
        return pages[directory[d] * PAGE + local(x, y, z)] & 0xFF;
    }

    @Override
    public int solidClass(float value) {
        if (value == DEFAULT_FRICTION) {
            return CellClass.SOLID_DEFAULT;
        }
        for (int c = 2; c <= frictionClasses; c++) {
            if (Float.floatToRawIntBits(friction[c]) == Float.floatToRawIntBits(value)) {
                return c;
            }
        }
        if (frictionClasses >= CellClass.LAST_SOLID) {
            throw new IllegalStateException("more than 253 distinct block friction values");
        }
        int c = ++frictionClasses;
        friction[c] = value;
        return c;
    }

    @Override
    public float friction(int cellClass) {
        return friction[cellClass & 0xFF];
    }

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
        int page = directory[d];
        if (page >= 0) {
            return page;
        }
        if (freeCount == 0) {
            growPages(pageCapacity * 2);
        }
        page = freePages[--freeCount];
        if (fillComplex) {
            Arrays.fill(pages, page * PAGE, (page + 1) * PAGE, (byte) CellClass.COMPLEX);
        }
        directory[d] = page;
        return page;
    }

    private void growPages(int newCapacity) {
        if ((long) newCapacity * PAGE > Integer.MAX_VALUE - 64) {
            throw new IllegalStateException("terrain page pool exhausted");
        }
        pages = Arrays.copyOf(pages, newCapacity * PAGE);
        int[] free = Arrays.copyOf(freePages, newCapacity);
        for (int p = newCapacity - 1; p >= pageCapacity; p--) {
            free[freeCount++] = p;
        }
        freePages = free;
        pageCapacity = newCapacity;
    }

    @Override
    public void close() {
        pages = new byte[0];
        Arrays.fill(directory, -1);
    }
}
