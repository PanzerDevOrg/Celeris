package net.bichal.mods.celeris.physics;

import java.util.Arrays;

import static net.bichal.mods.celeris.physics.RawMemory.UNSAFE;

/**
 * {@link TerrainView} on raw off-heap memory for the JNI engine: an int32
 * section directory, a growable pool of 4 KiB pages, a 256-entry float
 * friction table and a {@code cp_terrain} header pointing at all three, which
 * is what the native kernel receives. Same behaviour as {@code SegmentTerrain}.
 */
@SuppressWarnings("removal")
final class JniTerrain implements TerrainView {

    // struct cp_terrain on the 64-bit targets Celeris ships natives for: three pointers, then six int32.
    private static final long H_DIRECTORY = 0;
    private static final long H_PAGES = 8;
    private static final long H_FRICTION = 16;
    private static final long H_ORIGIN_X = 24;
    private static final long H_SIZE_X = 36;
    private static final long HEADER_BYTES = 48;

    private static final int PAGE = SECTION_BYTES;
    private static final float DEFAULT_FRICTION = 0.6F;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int sections;
    private final RawMemory header;
    private final RawMemory directory;
    private final RawMemory friction;
    private RawMemory pages;
    private int pageCapacity;
    private int[] freePages;
    private int freeCount;
    private int originX;
    private int originY;
    private int originZ;
    private int frictionClasses = 1;
    private boolean closed;

    JniTerrain(int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > (1 << 24)) {
            throw new IllegalArgumentException("terrain window out of range: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sections = sizeX * sizeY * sizeZ;
        header = RawMemory.allocate(HEADER_BYTES, 64);
        directory = RawMemory.allocate((long) sections * Integer.BYTES, 64);
        UNSAFE.setMemory(directory.address, directory.size, (byte) 0xFF); // every entry -1: unloaded
        friction = RawMemory.allocate(256L * Float.BYTES, 64);
        for (int c = 0; c < 256; c++) {
            UNSAFE.putFloat(friction.address + ((long) c << 2), DEFAULT_FRICTION);
        }
        UNSAFE.putLong(header.address + H_DIRECTORY, directory.address);
        UNSAFE.putLong(header.address + H_FRICTION, friction.address);
        UNSAFE.putInt(header.address + H_SIZE_X, sizeX);
        UNSAFE.putInt(header.address + H_SIZE_X + 4, sizeY);
        UNSAFE.putInt(header.address + H_SIZE_X + 8, sizeZ);
        growPages(Math.min(sections, 64));
        writeOrigin();
    }

    /** Address of the {@code cp_terrain} header. */
    long header() {
        return header.address;
    }

    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("terrain closed");
        }
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
        checkOpen();
        originX = sectionX;
        originY = sectionY;
        originZ = sectionZ;
        UNSAFE.setMemory(directory.address, directory.size, (byte) 0xFF);
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
        checkOpen();
        if (offset < 0 || offset > cells.length - PAGE) {
            throw new IndexOutOfBoundsException("section needs " + PAGE + " bytes at " + offset + " of " + cells.length);
        }
        int page = pageFor(sectionX, sectionY, sectionZ, false);
        if (page >= 0) {
            UNSAFE.copyMemory(cells, RawMemory.BYTE_ARRAY_BASE + offset, null, pageAddress(page), PAGE);
        }
    }

    @Override
    public void clearSection(int sectionX, int sectionY, int sectionZ) {
        checkOpen();
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return;
        }
        int page = UNSAFE.getInt(dirAddress(d));
        if (page >= 0) {
            UNSAFE.putInt(dirAddress(d), -1);
            freePages[freeCount++] = page;
        }
    }

    @Override
    public void setCell(int x, int y, int z, int cellClass) {
        checkOpen();
        int page = pageFor(x >> 4, y >> 4, z >> 4, true);
        if (page >= 0) {
            UNSAFE.putByte(pageAddress(page) + local(x, y, z), (byte) cellClass);
        }
    }

    @Override
    public int cell(int x, int y, int z) {
        checkOpen();
        int d = dirIndex(x >> 4, y >> 4, z >> 4);
        if (d < 0) {
            return CellClass.COMPLEX;
        }
        int page = UNSAFE.getInt(dirAddress(d));
        if (page < 0) {
            return CellClass.COMPLEX;
        }
        return UNSAFE.getByte(pageAddress(page) + local(x, y, z)) & 0xFF;
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
        UNSAFE.putFloat(friction.address + ((long) c << 2), value);
        return c;
    }

    @Override
    public float friction(int cellClass) {
        return UNSAFE.getFloat(friction.address + ((long) (cellClass & 0xFF) << 2));
    }

    /** Directory index of a section, or -1 outside the window. */
    private int dirIndex(int sectionX, int sectionY, int sectionZ) {
        int sx = sectionX - originX;
        int sy = sectionY - originY;
        int sz = sectionZ - originZ;
        if (Integer.compareUnsigned(sx, sizeX) >= 0 | Integer.compareUnsigned(sy, sizeY) >= 0
                | Integer.compareUnsigned(sz, sizeZ) >= 0) {
            return -1;
        }
        return (sy * sizeZ + sz) * sizeX + sx;
    }

    private long dirAddress(int index) {
        return directory.address + ((long) index << 2);
    }

    private long pageAddress(int page) {
        return pages.address + (long) page * PAGE;
    }

    private static int local(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private int pageFor(int sectionX, int sectionY, int sectionZ, boolean fillComplex) {
        int d = dirIndex(sectionX, sectionY, sectionZ);
        if (d < 0) {
            return -1;
        }
        int page = UNSAFE.getInt(dirAddress(d));
        if (page >= 0) {
            return page;
        }
        if (freeCount == 0) {
            growPages(pageCapacity * 2);
        }
        page = freePages[--freeCount];
        if (fillComplex) {
            UNSAFE.setMemory(pageAddress(page), PAGE, (byte) CellClass.COMPLEX);
        }
        UNSAFE.putInt(dirAddress(d), page);
        return page;
    }

    private void growPages(int newCapacity) {
        // +64: the kernels never read past a page, but keep a cache line of slack anyway.
        RawMemory grown = RawMemory.allocate((long) newCapacity * PAGE + 64, 64);
        if (pages != null) {
            UNSAFE.copyMemory(pages.address, grown.address, (long) pageCapacity * PAGE);
            pages.free();
        }
        int[] free = freePages == null ? new int[newCapacity] : Arrays.copyOf(freePages, newCapacity);
        for (int p = newCapacity - 1; p >= pageCapacity; p--) {
            free[freeCount++] = p;
        }
        freePages = free;
        pages = grown;
        pageCapacity = newCapacity;
        UNSAFE.putLong(header.address + H_PAGES, pages.address);
    }

    private void writeOrigin() {
        UNSAFE.putInt(header.address + H_ORIGIN_X, originX);
        UNSAFE.putInt(header.address + H_ORIGIN_X + 4, originY);
        UNSAFE.putInt(header.address + H_ORIGIN_X + 8, originZ);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pages.free();
        directory.free();
        friction.free();
        header.free();
    }
}
