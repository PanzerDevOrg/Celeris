package com.panzer.mods.celeris.physics;

import static com.panzer.mods.celeris.physics.BodyLayout.LANE_PAD;

/**
 * Reference implementation of the physics kernel, in plain Java over a
 * {@link HeapBodyBatch}. It is both the engine on JVMs without FFM and the
 * specification: {@code native/src/cp_kernel.inc} and {@code
 * cp_broadphase.cpp} are line-for-line ports of it, and the parity tests
 * require bit-identical results. Keep the three in lock-step.
 *
 * <p>Semantics: Minecraft 1.21 {@code Entity.move} + {@code ItemEntity.tick}
 * restricted to terrain of full cubes (see {@link CellClass}); constants keep
 * vanilla's float literals widened to double exactly as javac does. No
 * {@code Math.fma} outside {@link PhysicsMode#FUSED}, so results match the
 * native kernel compiled with {@code -ffp-contract=off}.
 */
final class JavaPhysicsKernel {

    static final String NAME = "java";

    static final double EPS = 1.0e-7;
    static final double MTH_EQUAL_EPS = 1.0E-5F;
    static final double HOLD_SPEED_SQR = 1.0E-5F;
    static final double SUPPORT_EPS = 1.0e-6;
    static final double ON_POS_LEGACY = 0.2F;
    static final double MIN_MOVE_SQR = 1.0e-7;
    static final int MAX_CELLS = 512;
    static final int NO_RANK = Integer.MAX_VALUE;
    static final double MIN_CELL = 0.0625;

    /** Per-thread scratch (one per worker, created once). */
    private static final ThreadLocal<Local> LOCALS = ThreadLocal.withInitial(Local::new);

    private JavaPhysicsKernel() {
    }

    // ------------------------------------------------------------------ step

    static int step(HeapBodyBatch b, HeapTerrain terrain, int begin, int end, int mode) {
        if (begin >= end) {
            return 0;
        }
        int lanesEnd = Math.min((end + LANE_PAD - 1) & -LANE_PAD, b.capacity());
        boolean fused = (mode & 0xFF) == PhysicsMode.FUSED.nativeMode();
        double[] vx = b.vx, vy = b.vy, vz = b.vz, g = b.gravity, fh = b.factorH, fv = b.factorV;
        if (!fused) {
            for (int i = begin; i < lanesEnd; i++) {
                vy[i] = vy[i] - g[i];
            }
        }

        Local local = LOCALS.get();
        local.bind(terrain);
        int deferred = 0;
        int[] list = b.deferredList;
        for (int i = begin; i < end; i++) {
            if (collideBody(b, i, mode, local)) {
                list[begin + deferred++] = i;
            }
        }
        for (int i = end; i < lanesEnd; i++) {
            fh[i] = 1.0;
            fv[i] = 1.0;
        }

        if (fused) {
            for (int i = begin; i < lanesEnd; i++) {
                vx[i] = vx[i] * fh[i];
                vy[i] = Math.fma(vy[i], fv[i], -g[i]);
                vz[i] = vz[i] * fh[i];
            }
        } else {
            for (int i = begin; i < lanesEnd; i++) {
                vx[i] = vx[i] * fh[i];
                vy[i] = vy[i] * fv[i];
                vz[i] = vz[i] * fh[i];
            }
        }
        return deferred;
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
        int[] directory;
        byte[] pages;
        float[] friction;
        int ox, oy, oz, sx, sy, sz;

        void bind(HeapTerrain t) {
            directory = t.directory;
            pages = t.pages;
            friction = t.friction;
            ox = t.originX;
            oy = t.originY;
            oz = t.originZ;
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
            int page = directory[(dy * sz + dz) * sx + dx];
            if (page < 0) {
                return CellClass.COMPLEX;
            }
            return pages[page * TerrainView.SECTION_BYTES + HeapTerrain.local(x, y, z)] & 0xFF;
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

    /**
     * VoxelShape.collide for every full cube in the list, along axis {@code a}.
     * Nearest layer first: the first layer with a solid cube in the footprint
     * decides (vanilla takes min/max over all cubes, the same value). The
     * |d| &lt; EPS snap reproduces Shapes.collide's check at the top of each
     * list iteration: it fires when any shape follows the first cube that
     * brought d within EPS of zero.
     */
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
                        first = Math.min(l.rank(c[0], c[1], c[2]), first);
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

    /**
     * Level.findSupportingBlock over the full cubes strictly intersecting the
     * box: nearest block centre to the entity position, ties to the greatest
     * BlockPos (Vec3i.compareTo: y, then z, then x). Result in {@code l.support}.
     */
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

    private static boolean defer(HeapBodyBatch b, int i, int deferredFlags) {
        b.factorH[i] = 1.0;
        b.factorV[i] = 1.0;
        b.flags[i] = deferredFlags;
        return true;
    }

    /** One body, one tick, between gravity and drag. Returns true when deferred to vanilla. */
    static boolean collideBody(HeapBodyBatch b, int i, int mode, Local l) {
        int flags = b.flags[i];
        int phase = (flags >>> BodyFlags.PHASE_SHIFT) & 3;
        int nextPhase = ((phase + 1) & 3) << BodyFlags.PHASE_SHIFT;

        double x = b.px[i], y = b.py[i], z = b.pz[i];
        double vx = b.vx[i], vy = b.vy[i], vz = b.vz[i];
        double hw = b.halfWidth[i], h = b.height[i];
        boolean onGround = (flags & BodyFlags.ON_GROUND) != 0;
        boolean mayChange = vx != 0.0 | vy != 0.0 | vz != 0.0 | b.gravity[i] != 0.0;

        int deferredFlags = (flags & ~(BodyFlags.DEFERRED | BodyFlags.MOVED | BodyFlags.HELD | BodyFlags.PHASE_MASK))
                | BodyFlags.DEFERRED | nextPhase;

        double x0 = x - hw, x1 = x + hw, y0 = y, y1 = y + h, z0 = z - hw, z1 = z + hw;

        // ItemEntity: moves only if !onGround || horizontalDistanceSqr() > 1.0E-5F || (tickCount + id) % 4 == 0
        if ((flags & BodyFlags.THROTTLE_RESTING) != 0 && onGround && !(vx * vx + vz * vz > HOLD_SPEED_SQR) && phase != 0) {
            // noPhysics is decided before that test: a box overlapping a block pushes the
            // item out (moveTowardsClosestSpace), which may make it move this tick.
            for (int cz = ifloor(z0 + EPS); cz <= iceil(z1 - EPS) - 1; cz++) {
                for (int cy = ifloor(y0 + EPS); cy <= iceil(y1 - EPS) - 1; cy++) {
                    for (int cx = ifloor(x0 + EPS); cx <= iceil(x1 - EPS) - 1; cx++) {
                        int c = l.terrain(cx, cy, cz);
                        if (c == CellClass.COMPLEX || solid(c)) {
                            return defer(b, i, deferredFlags);
                        }
                    }
                }
            }
            b.factorH[i] = 1.0;
            b.factorV[i] = 1.0;
            b.flags[i] = (flags & ~(BodyFlags.DEFERRED | BodyFlags.MOVED | BodyFlags.PHASE_MASK))
                    | BodyFlags.HELD | nextPhase | (mayChange ? BodyFlags.MOVED : 0);
            return false;
        }
        double mx = vx, my = vy, mz = vz;

        // AABB.expandTowards
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
            return defer(b, i, deferredFlags);
        }

        // Gather. Anything not modelled exactly in the swept region defers the body.
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
            return defer(b, i, deferredFlags);
        }

        // ItemEntity: noPhysics = !level.noCollision(this, box.deflate(1.0E-7)) -> moveTowardsClosestSpace
        if (anySolid) {
            for (int cz = ifloor(z0 + EPS); cz <= iceil(z1 - EPS) - 1; cz++) {
                for (int cy = ifloor(y0 + EPS); cy <= iceil(y1 - EPS) - 1; cy++) {
                    for (int cx = ifloor(x0 + EPS); cx <= iceil(x1 - EPS) - 1; cx++) {
                        if (solid(l.at(cx, cy, cz))) {
                            return defer(b, i, deferredFlags);
                        }
                    }
                }
            }
        }

        // Entity.collideWithShapes: Y, then the larger horizontal axis last.
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

        // Entity.move
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

        // checkSupportingBlock / getOnPos, on the bounding box rebuilt by setPos
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

        // getOnPosLegacy: only the default updateEntityAfterFallOn / stepOn / fallOn is modelled.
        if (vColl) {
            int c = hasSupport
                    ? l.at(sup[0], ifloor(ny - ON_POS_LEGACY), sup[2])
                    : l.at(ifloor(nx), ifloor(ny - ON_POS_LEGACY), ifloor(nz));
            if (c == CellClass.COMPLEX) {
                return defer(b, i, deferredFlags);
            }
        }

        // getBlockPosBelowThatAffectsMyMovement: its friction on the ground, and
        // its getSpeedFactor always -- Entity.move applies the speed factor of
        // the block below even mid-air (soul sand, honey: COMPLEX). Items look
        // almost a whole block down (getOnPos(0.999999F)).
        double belowY = ny - b.belowOffset[i];
        int below = hasSupport
                ? l.at(sup[0], ifloor(belowY), sup[2])
                : l.at(ifloor(nx), ifloor(belowY), ifloor(nz));
        if (below == CellClass.COMPLEX) {
            return defer(b, i, deferredFlags);
        }
        double factorH = b.dragAirH[i];
        if (ground) {
            factorH = l.friction[below] * (float) b.groundScale[i];
        }

        // Commit
        b.px[i] = nx;
        b.py[i] = ny;
        b.pz[i] = nz;
        if (hitX | hitZ) {
            b.vx[i] = hitX ? 0.0 : vx;
            b.vz[i] = hitZ ? 0.0 : vz;
        }
        if (vColl) {
            b.vy[i] = vy * 0.0; // Block.updateEntityAfterFallOn: multiply(1.0, 0.0, 1.0)
        }
        b.factorH[i] = factorH;
        b.factorV[i] = b.dragV[i];
        boolean posChanged = nx != x | ny != y | nz != z;
        b.flags[i] = (flags & ~(BodyFlags.OUT_MASK | BodyFlags.ON_GROUND | BodyFlags.GROUND_NO_BLOCKS | BodyFlags.PHASE_MASK))
                | nextPhase
                | (ground ? BodyFlags.ON_GROUND : 0)
                | (noBlocks ? BodyFlags.GROUND_NO_BLOCKS : 0)
                | ((hitX | hitZ) ? BodyFlags.H_COLLISION : 0)
                | (vColl ? BodyFlags.V_COLLISION : 0)
                | ((posChanged | mayChange) ? BodyFlags.MOVED : 0);
        return false;
    }

    // ------------------------------------------------------------ broadphase

    static int hashCell(int x, int y, int z) {
        return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
    }

    /** Spatial hash + counting sort, O(n); same hash and visiting order as cp_broadphase.cpp. */
    static int broadphase(HeapBodyBatch b, int count, double margin, int[] pairs, int pairCapacity) {
        if (count < 2 || count > b.capacity()) {
            return 0;
        }
        double[] px = b.px, py = b.py, pz = b.pz, hw = b.halfWidth, hh = b.height;
        int[] cellHash = b.cellHash, sorted = b.sorted, buckets = b.buckets;

        double maxExt = 0.0;
        for (int i = 0; i < count; i++) {
            double w = hw[i] + hw[i];
            double e = w > hh[i] ? w : hh[i];
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

        int bucketsN = BodyLayout.bucketCount(b.capacity());
        int mask = bucketsN - 1;
        for (int i = 0; i < count; i++) {
            cellHash[i] = hashCell(ifloor((px[i] - hw[i]) * inv), ifloor(py[i] * inv), ifloor((pz[i] - hw[i]) * inv)) & mask;
        }

        // Counting sort; afterwards buckets[b] is the END of bucket b, its start buckets[b - 1].
        java.util.Arrays.fill(buckets, 0, bucketsN + 1, 0);
        for (int i = 0; i < count; i++) {
            buckets[cellHash[i]]++;
        }
        int run = 0;
        for (int k = 0; k < bucketsN; k++) {
            int c = buckets[k];
            buckets[k] = run;
            run += c;
        }
        for (int i = 0; i < count; i++) {
            sorted[buckets[cellHash[i]]++] = i;
        }

        long found = 0;
        int[] seen = LOCALS.get().seen;
        for (int i = 0; i < count; i++) {
            double ax0 = px[i] - hw[i] - margin, ax1 = px[i] + hw[i] + margin;
            double ay0 = py[i] - margin, ay1 = py[i] + hh[i] + margin;
            double az0 = pz[i] - hw[i] - margin, az1 = pz[i] + hw[i] + margin;
            int cx = ifloor((px[i] - hw[i]) * inv), cy = ifloor(py[i] * inv), cz = ifloor((pz[i] - hw[i]) * inv);
            int seenN = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int bucket = hashCell(cx + dx, cy + dy, cz + dz) & mask;
                        boolean dup = false;
                        for (int t = 0; t < seenN; t++) {
                            dup |= seen[t] == bucket;
                        }
                        if (dup) {
                            continue;
                        }
                        seen[seenN++] = bucket;
                        int lo = bucket == 0 ? 0 : buckets[bucket - 1];
                        int hi = buckets[bucket];
                        for (int q = lo; q < hi; q++) {
                            int j = sorted[q];
                            if (j <= i) {
                                continue;
                            }
                            double bx0 = px[j] - hw[j], bx1 = px[j] + hw[j];
                            double bz0 = pz[j] - hw[j], bz1 = pz[j] + hw[j];
                            boolean overlap = ax0 < bx1 & ax1 > bx0 & ay0 < py[j] + hh[j] & ay1 > py[j]
                                    & az0 < bz1 & az1 > bz0;
                            if (overlap) {
                                if (found < pairCapacity) {
                                    pairs[(int) (2 * found)] = i;
                                    pairs[(int) (2 * found + 1)] = j;
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
