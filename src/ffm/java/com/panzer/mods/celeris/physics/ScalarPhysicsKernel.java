package com.panzer.mods.celeris.physics;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.panzer.mods.celeris.physics.BodyLayout.*;

/**
 * Reference implementation of the kernel, in Java over the slab. This is the
 * specification: {@code native/src/cp_kernel.inc} and {@code
 * cp_broadphase.cpp} are line-for-line ports of it, and the parity tests
 * require bit-identical results. Keep the three in lock-step.
 *
 * <p>Semantics: Minecraft 1.21 {@code Entity.move} + {@code ItemEntity.tick}
 * restricted to terrain of full cubes (see {@link CellClass}); constants keep
 * vanilla's float literals widened to double exactly as javac does.
 *
 * <p>Optionally hands the two streaming passes to a {@link StreamKernel}
 * (Vector API). Only the per-body collision pass then runs scalar, and it is
 * gather-bound, not arithmetic-bound.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class ScalarPhysicsKernel implements PhysicsKernel {

    static final double EPS = 1.0e-7;
    static final double MTH_EQUAL_EPS = 1.0E-5F;
    static final double HOLD_SPEED_SQR = 1.0E-5F;
    static final double SUPPORT_EPS = 1.0e-6;
    static final double ON_POS_FRICTION = 0.500001F;
    static final double ON_POS_LEGACY = 0.2F;
    static final double MIN_MOVE_SQR = 1.0e-7;
    static final int MAX_CELLS = 512;
    static final int NO_RANK = Integer.MAX_VALUE;
    static final double MIN_CELL = 0.0625;

    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;

    private final StreamKernel streams;
    /** Per-thread scratch for the gathered cells (one Local per worker, created once). */
    private final ThreadLocal<Local> locals = ThreadLocal.withInitial(Local::new);

    ScalarPhysicsKernel(StreamKernel streams) {
        this.streams = streams;
    }

    @Override
    public String name() {
        return streams != null ? "java+" + streams.name() : "java";
    }

    // ------------------------------------------------------------------ step

    @Override
    public int step(MemorySegment slab, int capacity, SegmentTerrain terrain, int begin, int end, int mode) {
        if (begin >= end) {
            return 0;
        }
        int lanesEnd = Math.min((end + LANE_PAD - 1) & -LANE_PAD, capacity);
        boolean fused = (mode & 0xFF) == PhysicsMode.FUSED.nativeMode();
        if (!fused) {
            if (streams != null) {
                streams.gravity(slab, capacity, begin, lanesEnd);
            } else {
                long vy = f64Offset(VEL_Y, capacity);
                long g = f64Offset(GRAVITY, capacity);
                for (int i = begin; i < lanesEnd; i++) {
                    long o = (long) i << 3;
                    slab.set(F64, vy + o, slab.get(F64, vy + o) - slab.get(F64, g + o));
                }
            }
        }

        Local local = locals.get();
        local.bind(terrain);
        int deferred = 0;
        long list = u32Offset(DEFERRED_LIST, capacity);
        for (int i = begin; i < end; i++) {
            if (collideBody(slab, capacity, i, mode, local)) {
                slab.set(U32, list + ((long) (begin + deferred++) << 2), i);
            }
        }
        long fhCol = f64Offset(FACTOR_H, capacity);
        long fvCol = f64Offset(FACTOR_V, capacity);
        for (int i = end; i < lanesEnd; i++) {
            slab.set(F64, fhCol + ((long) i << 3), 1.0);
            slab.set(F64, fvCol + ((long) i << 3), 1.0);
        }

        if (streams != null) {
            streams.damp(slab, capacity, begin, lanesEnd, fused);
        } else {
            damp(slab, capacity, begin, lanesEnd, fused);
        }
        return deferred;
    }

    static void damp(MemorySegment slab, int capacity, int begin, int end, boolean fused) {
        long vx = f64Offset(VEL_X, capacity), vy = f64Offset(VEL_Y, capacity), vz = f64Offset(VEL_Z, capacity);
        long fh = f64Offset(FACTOR_H, capacity), fv = f64Offset(FACTOR_V, capacity), g = f64Offset(GRAVITY, capacity);
        for (int i = begin; i < end; i++) {
            long o = (long) i << 3;
            double h = slab.get(F64, fh + o);
            slab.set(F64, vx + o, slab.get(F64, vx + o) * h);
            slab.set(F64, vz + o, slab.get(F64, vz + o) * h);
            slab.set(F64, vy + o, fused
                    ? Math.fma(slab.get(F64, vy + o), slab.get(F64, fv + o), -slab.get(F64, g + o))
                    : slab.get(F64, vy + o) * slab.get(F64, fv + o));
        }
    }

    /** Cells of the expanded box, z-major / y / x like vanilla's Cursor3D. */
    static final class Local {
        final byte[] cell = new byte[MAX_CELLS];
        final int[] lo = new int[3];
        final int[] n = new int[3];
        final int[] c = new int[3];
        final int[] support = new int[3];
        final double[] boxLo = new double[3];
        final double[] boxHi = new double[3];
        final int[] seen = new int[27];
        int lastRank;
        MemorySegment directory;
        MemorySegment pages;
        MemorySegment friction;
        int ox, oy, oz, sx, sy, sz;

        void bind(SegmentTerrain t) {
            directory = t.directory;
            pages = t.pages;
            friction = t.friction;
            ox = t.originX();
            oy = t.originY();
            oz = t.originZ();
            sx = t.sizeX();
            sy = t.sizeY();
            sz = t.sizeZ();
        }

        int terrain(int x, int y, int z) {
            int dx = (x >> 4) - ox, dy = (y >> 4) - oy, dz = (z >> 4) - oz;
            if (Integer.compareUnsigned(dx, sx) >= 0 | Integer.compareUnsigned(dy, sy) >= 0
                    | Integer.compareUnsigned(dz, sz) >= 0) {
                return CellClass.COMPLEX;
            }
            int page = directory.getAtIndex(U32, ((long) dy * sz + dz) * sx + dx);
            if (page < 0) {
                return CellClass.COMPLEX;
            }
            return pages.get(ValueLayout.JAVA_BYTE, (long) page * TerrainView.SECTION_BYTES + SegmentTerrain.local(x, y, z)) & 0xFF;
        }

        float terrainFriction(int cellClass) {
            return friction.getAtIndex(F32, cellClass);
        }

        int rank(int x, int y, int z) {
            return ((z - lo[2]) * n[1] + (y - lo[1])) * n[0] + (x - lo[0]);
        }

        int at(int x, int y, int z) {
            int lx = x - lo[0], ly = y - lo[1], lz = z - lo[2];
            if (Integer.compareUnsigned(lx, n[0]) < 0 & Integer.compareUnsigned(ly, n[1]) < 0
                    & Integer.compareUnsigned(lz, n[2]) < 0) {
                return cell[(lz * n[1] + ly) * n[0] + lx] & 0xFF;
            }
            return terrain(x, y, z);
        }

        int atC() {
            return at(c[0], c[1], c[2]);
        }
    }

    static int ifloor(double v) {
        return (int) Math.floor(v);
    }

    static int iceil(double v) {
        return (int) Math.ceil(v);
    }

    static boolean solid(int cell) {
        return cell != CellClass.AIR && cell != CellClass.COMPLEX;
    }

    /** VoxelShape.collide over every full cube in the list, along axis {@code a}; see cp_kernel.inc. */
    static double sweep(Local l, int a, double d, double[] lo, double[] hi) {
        if (Math.abs(d) < EPS) {
            return 0.0;
        }
        int p = a == 0 ? 1 : 0;
        int q = a == 2 ? 1 : 2;
        int p0 = ifloor(lo[p] + EPS), p1 = ifloor(hi[p] - EPS);
        int q0 = ifloor(lo[q] + EPS), q1 = ifloor(hi[q] - EPS);
        if (p0 > p1 | q0 > q1) {
            return d;
        }
        boolean up = d > 0.0;
        int cc = up ? ifloor(hi[a] - EPS) + 1 : ifloor(lo[a] + EPS) - 1;
        int stop = up ? l.lo[a] + l.n[a] - 1 : l.lo[a];
        int step = up ? 1 : -1;
        int[] c = l.c;
        for (; up ? cc <= stop : cc >= stop; cc += step) {
            int first = NO_RANK;
            c[a] = cc;
            for (int pp = p0; pp <= p1; pp++) {
                c[p] = pp;
                for (int qq = q0; qq <= q1; qq++) {
                    c[q] = qq;
                    if (solid(l.atC())) {
                        int r = l.rank(c[0], c[1], c[2]);
                        first = Math.min(r, first);
                    }
                }
            }
            if (first != NO_RANK) {
                double face = up ? (double) cc - hi[a] : (double) (cc + 1) - lo[a];
                double nd = up ? (face < d ? face : d) : (face > d ? face : d);
                if (Math.abs(nd) < EPS && first < l.lastRank) {
                    return 0.0;
                }
                return nd;
            }
        }
        return d;
    }

    /** Level.findSupportingBlock; see cp_kernel.inc. Result in {@code l.support}. */
    static boolean findSupport(Local l, double bx0, double by0, double bz0, double bx1, double by1, double bz1,
                               double px, double py, double pz) {
        int x0 = ifloor(bx0), x1 = iceil(bx1) - 1;
        int y0 = ifloor(by0), y1 = iceil(by1) - 1;
        int z0 = ifloor(bz0), z1 = iceil(bz1) - 1;
        int[] out = l.support;
        boolean found = false;
        double best = 0.0;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    if (!solid(l.at(x, y, z))) {
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
        return found;
    }

    /** One body, one tick, between gravity and drag. Returns true when deferred. */
    static boolean collideBody(MemorySegment s, int cap, int i, int mode, Local l) {
        long o = (long) i << 3;
        long flagsAt = u32Offset(FLAGS, cap) + ((long) i << 2);
        long fhAt = f64Offset(FACTOR_H, cap) + o;
        long fvAt = f64Offset(FACTOR_V, cap) + o;
        int flags = s.get(U32, flagsAt);
        int phase = (flags >>> BodyFlags.PHASE_SHIFT) & 3;
        int nextPhase = ((phase + 1) & 3) << BodyFlags.PHASE_SHIFT;

        double x = s.get(F64, f64Offset(POS_X, cap) + o);
        double y = s.get(F64, f64Offset(POS_Y, cap) + o);
        double z = s.get(F64, f64Offset(POS_Z, cap) + o);
        double vx = s.get(F64, f64Offset(VEL_X, cap) + o);
        double vy = s.get(F64, f64Offset(VEL_Y, cap) + o);
        double vz = s.get(F64, f64Offset(VEL_Z, cap) + o);
        double hw = s.get(F64, f64Offset(HALF_WIDTH, cap) + o);
        double h = s.get(F64, f64Offset(HEIGHT, cap) + o);
        boolean onGround = (flags & BodyFlags.ON_GROUND) != 0;
        boolean mayChange = vx != 0.0 | vy != 0.0 | vz != 0.0 | s.get(F64, f64Offset(GRAVITY, cap) + o) != 0.0;

        if ((flags & BodyFlags.THROTTLE_RESTING) != 0 && onGround && !(vx * vx + vz * vz > HOLD_SPEED_SQR) && phase != 0) {
            s.set(F64, fhAt, 1.0);
            s.set(F64, fvAt, 1.0);
            s.set(U32, flagsAt, (flags & ~(BodyFlags.DEFERRED | BodyFlags.MOVED | BodyFlags.PHASE_MASK))
                    | BodyFlags.HELD | nextPhase | (mayChange ? BodyFlags.MOVED : 0));
            return false;
        }

        int deferredFlags = (flags & ~(BodyFlags.DEFERRED | BodyFlags.MOVED | BodyFlags.HELD | BodyFlags.PHASE_MASK))
                | BodyFlags.DEFERRED | nextPhase;

        double x0 = x - hw, x1 = x + hw, y0 = y, y1 = y + h, z0 = z - hw, z1 = z + hw;
        double mx = vx, my = vy, mz = vz;

        double e0x = mx < 0.0 ? x0 + mx : x0, e0y = my < 0.0 ? y0 + my : y0, e0z = mz < 0.0 ? z0 + mz : z0;
        double e1x = mx > 0.0 ? x1 + mx : x1, e1y = my > 0.0 ? y1 + my : y1, e1z = mz > 0.0 ? z1 + mz : z1;
        l.lo[0] = ifloor(e0x);
        l.lo[1] = ifloor(e0y);
        l.lo[2] = ifloor(e0z);
        l.n[0] = Math.max(0, iceil(e1x) - l.lo[0]);
        l.n[1] = Math.max(0, iceil(e1y) - l.lo[1]);
        l.n[2] = Math.max(0, iceil(e1z) - l.lo[2]);
        long cells = (long) l.n[0] * l.n[1] * l.n[2];
        if (cells > MAX_CELLS) {
            return defer(s, fhAt, fvAt, flagsAt, deferredFlags);
        }

        boolean anySolid = false;
        boolean anyComplex = false;
        l.lastRank = NO_RANK;
        int k = 0;
        for (int cz = 0; cz < l.n[2]; cz++) {
            for (int cy = 0; cy < l.n[1]; cy++) {
                for (int cx = 0; cx < l.n[0]; cx++, k++) {
                    int c = l.terrain(l.lo[0] + cx, l.lo[1] + cy, l.lo[2] + cz);
                    l.cell[k] = (byte) c;
                    anyComplex |= c == CellClass.COMPLEX;
                    if (solid(c)) {
                        anySolid = true;
                        l.lastRank = k;
                    }
                }
            }
        }
        if (anyComplex) {
            return defer(s, fhAt, fvAt, flagsAt, deferredFlags);
        }

        if (anySolid) {
            for (int cz = ifloor(z0 + EPS); cz <= iceil(z1 - EPS) - 1; cz++) {
                for (int cy = ifloor(y0 + EPS); cy <= iceil(y1 - EPS) - 1; cy++) {
                    for (int cx = ifloor(x0 + EPS); cx <= iceil(x1 - EPS) - 1; cx++) {
                        if (solid(l.at(cx, cy, cz))) {
                            return defer(s, fhAt, fvAt, flagsAt, deferredFlags);
                        }
                    }
                }
            }
        }

        double dx = mx, dy = my, dz = mz;
        if (anySolid && mx * mx + my * my + mz * mz != 0.0) {
            double[] lo = l.boxLo;
            double[] hi = l.boxHi;
            lo[0] = x0;
            lo[1] = y0;
            lo[2] = z0;
            hi[0] = x1;
            hi[1] = y1;
            hi[2] = z1;
            if (dy != 0.0) {
                dy = sweep(l, 1, dy, lo, hi);
                if (dy != 0.0) {
                    lo[1] += dy;
                    hi[1] += dy;
                }
            }
            boolean zFirst = Math.abs(dx) < Math.abs(dz);
            if (zFirst && dz != 0.0) {
                dz = sweep(l, 2, dz, lo, hi);
                if (dz != 0.0) {
                    lo[2] += dz;
                    hi[2] += dz;
                }
            }
            if (dx != 0.0) {
                dx = sweep(l, 0, dx, lo, hi);
                if (!zFirst && dx != 0.0) {
                    lo[0] += dx;
                    hi[0] += dx;
                }
            }
            if (!zFirst && dz != 0.0) {
                dz = sweep(l, 2, dz, lo, hi);
            }
        }

        double movedSqr = dx * dx + dy * dy + dz * dz;
        double nx = x, ny = y, nz = z;
        boolean apply = (mode & PhysicsMode.RULE_SMALL_MOVES) != 0
                ? movedSqr > MIN_MOVE_SQR || (mx * mx + my * my + mz * mz) - movedSqr < MIN_MOVE_SQR
                : movedSqr > MIN_MOVE_SQR;
        if (apply) {
            nx = x + dx;
            ny = y + dy;
            nz = z + dz;
        }
        boolean hitX = !(Math.abs(dx - mx) < MTH_EQUAL_EPS);
        boolean hitZ = !(Math.abs(dz - mz) < MTH_EQUAL_EPS);
        boolean vColl = my != dy;
        boolean ground = vColl && my < 0.0;

        double bx0 = nx - hw, bx1 = nx + hw, by0 = ny, bz0 = nz - hw, bz1 = nz + hw;
        int[] sup = l.support;
        sup[0] = 0;
        sup[1] = 0;
        sup[2] = 0;
        boolean hasSupport = false;
        boolean noBlocks = false;
        if (ground) {
            hasSupport = findSupport(l, bx0, by0 - SUPPORT_EPS, bz0, bx1, by0, bz1, nx, ny, nz);
            if (!hasSupport && (flags & BodyFlags.GROUND_NO_BLOCKS) == 0) {
                hasSupport = findSupport(l, bx0 - dx, by0 - SUPPORT_EPS, bz0 - dz, bx1 - dx, by0, bz1 - dz, nx, ny, nz);
            }
            noBlocks = !hasSupport;
        }

        if (vColl) {
            int c = hasSupport
                    ? l.at(sup[0], ifloor(ny - ON_POS_LEGACY), sup[2])
                    : l.at(ifloor(nx), ifloor(ny - ON_POS_LEGACY), ifloor(nz));
            if (c == CellClass.COMPLEX) {
                return defer(s, fhAt, fvAt, flagsAt, deferredFlags);
            }
        }

        double factorH = s.get(F64, f64Offset(DRAG_AIR_H, cap) + o);
        if (ground) {
            int c = hasSupport
                    ? l.at(sup[0], ifloor(ny - ON_POS_FRICTION), sup[2])
                    : l.at(ifloor(nx), ifloor(ny - ON_POS_FRICTION), ifloor(nz));
            if (c == CellClass.COMPLEX) {
                return defer(s, fhAt, fvAt, flagsAt, deferredFlags);
            }
            float friction = l.terrainFriction(c);
            factorH = friction * (float) s.get(F64, f64Offset(GROUND_SCALE, cap) + o);
        }

        s.set(F64, f64Offset(POS_X, cap) + o, nx);
        s.set(F64, f64Offset(POS_Y, cap) + o, ny);
        s.set(F64, f64Offset(POS_Z, cap) + o, nz);
        if (hitX | hitZ) {
            s.set(F64, f64Offset(VEL_X, cap) + o, hitX ? 0.0 : vx);
            s.set(F64, f64Offset(VEL_Z, cap) + o, hitZ ? 0.0 : vz);
        }
        if (vColl) {
            s.set(F64, f64Offset(VEL_Y, cap) + o, vy * 0.0);
        }
        s.set(F64, fhAt, factorH);
        s.set(F64, fvAt, s.get(F64, f64Offset(DRAG_V, cap) + o));
        boolean posChanged = nx != x | ny != y | nz != z;
        s.set(U32, flagsAt, (flags & ~(BodyFlags.OUT_MASK | BodyFlags.ON_GROUND | BodyFlags.GROUND_NO_BLOCKS | BodyFlags.PHASE_MASK))
                | nextPhase
                | (ground ? BodyFlags.ON_GROUND : 0)
                | (noBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0)
                | ((hitX | hitZ) ? BodyFlags.H_COLLISION : 0)
                | (vColl ? BodyFlags.V_COLLISION : 0)
                | ((posChanged | mayChange) ? BodyFlags.MOVED : 0));
        return false;
    }

    private static boolean defer(MemorySegment s, long fhAt, long fvAt, long flagsAt, int deferredFlags) {
        s.set(F64, fhAt, 1.0);
        s.set(F64, fvAt, 1.0);
        s.set(U32, flagsAt, deferredFlags);
        return true;
    }

    // ------------------------------------------------------------ broadphase

    static int hashCell(int x, int y, int z) {
        return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
    }

    @Override
    public int broadphase(MemorySegment s, int cap, int count, MemorySegment buckets, double margin,
                          MemorySegment pairs, int pairCapacity) {
        if (count < 2 || count > cap) {
            return 0;
        }
        long px = f64Offset(POS_X, cap), py = f64Offset(POS_Y, cap), pz = f64Offset(POS_Z, cap);
        long hwc = f64Offset(HALF_WIDTH, cap), hc = f64Offset(HEIGHT, cap);
        long hashCol = u32Offset(CELL_HASH, cap), sortedCol = u32Offset(SORTED, cap);

        double maxExt = 0.0;
        for (int i = 0; i < count; i++) {
            long o = (long) i << 3;
            double hwi = s.get(F64, hwc + o);
            double w = hwi + hwi;
            double hi = s.get(F64, hc + o);
            double e = w > hi ? w : hi;
            maxExt = e > maxExt ? e : maxExt;
        }
        double cell = maxExt + margin;
        if (!(cell > 0.0)) {
            return 0;
        }
        if (cell < MIN_CELL) {
            cell = MIN_CELL;
        }
        double inv = 1.0 / cell;

        int bucketsN = bucketCount(cap);
        int mask = bucketsN - 1;
        for (int i = 0; i < count; i++) {
            long o = (long) i << 3;
            double hwi = s.get(F64, hwc + o);
            int hsh = hashCell(ifloor((s.get(F64, px + o) - hwi) * inv), ifloor(s.get(F64, py + o) * inv),
                    ifloor((s.get(F64, pz + o) - hwi) * inv)) & mask;
            s.set(U32, hashCol + ((long) i << 2), hsh);
        }

        buckets.asSlice(0, (long) (bucketsN + 1) * Integer.BYTES).fill((byte) 0);
        for (int i = 0; i < count; i++) {
            long b = (long) s.get(U32, hashCol + ((long) i << 2)) << 2;
            buckets.set(U32, b, buckets.get(U32, b) + 1);
        }
        int run = 0;
        for (int b = 0; b < bucketsN; b++) {
            int c = buckets.getAtIndex(U32, b);
            buckets.setAtIndex(U32, b, run);
            run += c;
        }
        for (int i = 0; i < count; i++) {
            long b = (long) s.get(U32, hashCol + ((long) i << 2)) << 2;
            int at = buckets.get(U32, b);
            buckets.set(U32, b, at + 1);
            s.set(U32, sortedCol + ((long) at << 2), i);
        }

        long found = 0;
        int[] seen = locals.get().seen;
        for (int i = 0; i < count; i++) {
            long o = (long) i << 3;
            double xi = s.get(F64, px + o), yi = s.get(F64, py + o), zi = s.get(F64, pz + o);
            double hwi = s.get(F64, hwc + o), hi = s.get(F64, hc + o);
            double ax0 = xi - hwi - margin, ax1 = xi + hwi + margin;
            double ay0 = yi - margin, ay1 = yi + hi + margin;
            double az0 = zi - hwi - margin, az1 = zi + hwi + margin;
            int cx = ifloor((xi - hwi) * inv), cy = ifloor(yi * inv), cz = ifloor((zi - hwi) * inv);
            int seenN = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int b = hashCell(cx + dx, cy + dy, cz + dz) & mask;
                        boolean dup = false;
                        for (int t = 0; t < seenN; t++) {
                            dup |= seen[t] == b;
                        }
                        if (dup) {
                            continue;
                        }
                        seen[seenN++] = b;
                        int lo = b == 0 ? 0 : buckets.getAtIndex(U32, b - 1);
                        int hiIdx = buckets.getAtIndex(U32, b);
                        for (int q = lo; q < hiIdx; q++) {
                            int j = s.get(U32, sortedCol + ((long) q << 2));
                            if (j <= i) {
                                continue;
                            }
                            long oj = (long) j << 3;
                            double xj = s.get(F64, px + oj), yj = s.get(F64, py + oj), zj = s.get(F64, pz + oj);
                            double hwj = s.get(F64, hwc + oj), hj = s.get(F64, hc + oj);
                            double bx0 = xj - hwj, bx1 = xj + hwj, bz0 = zj - hwj, bz1 = zj + hwj;
                            boolean overlap = ax0 < bx1 & ax1 > bx0 & ay0 < yj + hj & ay1 > yj & az0 < bz1 & az1 > bz0;
                            if (overlap) {
                                if (found < pairCapacity) {
                                    pairs.setAtIndex(U32, 2 * found, i);
                                    pairs.setAtIndex(U32, 2 * found + 1, j);
                                }
                                found++;
                            }
                        }
                    }
                }
            }
        }
        return (int) Math.min(found, Integer.MAX_VALUE);
    }
}
