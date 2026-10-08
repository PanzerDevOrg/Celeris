// Body of the per-ISA kernel. Included by every cp_isa_*.cpp INSIDE
// `namespace cp::<isa> { namespace { ... } }`, after that file has defined
// gravity_lanes() and damp_lanes() for its instruction set.
//
// Why an .inc inside an anonymous namespace instead of a shared header:
// each cp_isa_*.cpp is compiled with different -m flags (-mavx2, -mavx512f,
// ...). Any inline function or template with external linkage that two of
// them shared would be emitted in both, and the linker keeps ONE copy
// (COMDAT) -- possibly the AVX-512 one, which the generic path would then
// execute on a CPU without AVX-512 (SIGILL, which the JVM cannot catch).
// Internal linkage gives every ISA its own private copy. For the same
// reason this file uses no std:: templates (std::min, std::max, ...): only
// <cmath>'s plain double overloads, which resolve to C library functions.
//
// Semantics: Minecraft 1.21 Entity.move() + ItemEntity.tick() restricted to
// terrain made of full cubes. The Java reference (JavaPhysicsKernel) is
// line-for-line the same algorithm; NativePhysicsParityTest checks both
// agree bit for bit on every ISA.

constexpr double EPS = 1.0e-7;                       // Shapes / VoxelShape epsilon
constexpr double MTH_EQUAL_EPS = (double) 1.0e-5f;   // Mth.equal
constexpr double HOLD_SPEED_SQR = (double) 1.0e-5f;  // ItemEntity: horizontalDistanceSqr() > 1.0E-5F
constexpr double SUPPORT_EPS = 1.0e-6;               // Entity.checkSupportingBlock
constexpr double ON_POS_LEGACY = (double) 0.2f;        // getOnPosLegacy
constexpr double MIN_MOVE_SQR = 1.0e-7;
constexpr int32_t MAX_CELLS = 512;
constexpr int32_t NO_RANK = 0x7FFFFFFF;

struct Columns {
    double* f[CP_F64_COLUMNS];
    uint32_t* u[CP_U32_COLUMNS];
};

inline Columns columns(void* base, int32_t capacity) {
    Columns c;
    char* p = static_cast<char*>(base);
    const size_t f64 = (size_t) capacity * 8u;
    const size_t u32 = (size_t) capacity * 4u;
    for (int k = 0; k < CP_F64_COLUMNS; k++) c.f[k] = reinterpret_cast<double*>(p + k * f64);
    char* u = p + CP_F64_COLUMNS * f64;
    for (int k = 0; k < CP_U32_COLUMNS; k++) c.u[k] = reinterpret_cast<uint32_t*>(u + k * u32);
    return c;
}

// Inputs a body may carry and still be simulated; anything else (NaN, +-Inf,
// 1e300, teleports to +-3e9) is deferred to vanilla before any arithmetic.
// With them every coordinate the kernel floors stays far inside the clamp below.
constexpr double POS_LIMIT = 1.0e8;    // blocks; the world border ends at 3e7
constexpr double VEL_LIMIT = 1.0e7;    // blocks per tick
constexpr double SIZE_LIMIT = 1.0e4;   // half width / height, blocks

inline bool within(double v, double limit) { return std::fabs(v) <= limit; }  // false for NaN

// floor/ceil to int without undefined behaviour: the value is clamped to
// [INT_MIN/2, INT_MAX/2] BEFORE the cast (NaN -> INT_MIN/2), so the cast is
// always in range and sums/differences of two results cannot overflow.
// JavaPhysicsKernel.ifloor/iceil clamp the same way, so results stay equal.
constexpr double INT_LO = -1073741824.0;
constexpr double INT_HI = 1073741823.0;
// max then min: lo = v > INT_LO ? v : INT_LO (NaN -> INT_LO), then
// lo < INT_HI ? lo : INT_HI. On x86 that is exactly MAXSD/MINSD (SSE2, so
// every x86-64 CPU), written as intrinsics because compilers only emit them
// for the ternaries under -ffinite-math-only; elsewhere the ternaries.
inline double clamp_int(double v) {
#if defined(__SSE2__) || defined(_M_X64)
    const __m128d c = _mm_min_sd(_mm_max_sd(_mm_set_sd(v), _mm_set_sd(INT_LO)), _mm_set_sd(INT_HI));
    return _mm_cvtsd_f64(c);
#else
    const double lo = v > INT_LO ? v : INT_LO;
    return lo < INT_HI ? lo : INT_HI;
#endif
}
inline int32_t ifloor(double v) { return (int32_t) std::floor(clamp_int(v)); }
inline int32_t iceil(double v) { return (int32_t) std::ceil(clamp_int(v)); }
inline bool is_solid(uint32_t c) { return (c - 1u) < 0xFEu; }

// c ? a : b without a branch: the operands are data-dependent coin flips
// (signs of random velocities), which the branch predictor gets wrong half
// the time. Both sides are computed anyway; the bit select returns exactly
// one of them, so results stay bit-identical to the ternary.
inline double pick(bool c, double a, double b) {
    uint64_t ia, ib;
    std::memcpy(&ia, &a, 8);
    std::memcpy(&ib, &b, 8);
    const uint64_t m = (uint64_t) 0 - (uint64_t) c;
    const uint64_t r = (ia & m) | (ib & ~m);
    double out;
    std::memcpy(&out, &r, 8);
    return out;
}

inline uint32_t terrain_cell(const cp_terrain* t, int32_t x, int32_t y, int32_t z) {
    const int32_t sx = (x >> 4) - t->origin_x;
    const int32_t sy = (y >> 4) - t->origin_y;
    const int32_t sz = (z >> 4) - t->origin_z;
    // One unsigned compare per axis also rejects negatives; `|` keeps it a
    // single branch.
    if (((uint32_t) sx >= (uint32_t) t->size_x) | ((uint32_t) sy >= (uint32_t) t->size_y)
            | ((uint32_t) sz >= (uint32_t) t->size_z)) {
        return CP_CELL_COMPLEX;
    }
    const int32_t page = t->directory[((int64_t) sy * t->size_z + sz) * t->size_x + sx];
    if (page < 0) {
        return CP_CELL_COMPLEX;
    }
    const uint32_t local = (((uint32_t) y & 15u) << 8) | (((uint32_t) z & 15u) << 4) | ((uint32_t) x & 15u);
    return t->pages[(size_t) page * CP_PAGE_BYTES + local];
}

// The section page holding block (x, y, z), or nullptr when the section is
// outside the window or not loaded (terrain_cell's CP_CELL_COMPLEX cases).
inline const uint8_t* section_page(const cp_terrain* t, int32_t x, int32_t y, int32_t z) {
    const int32_t sx = (x >> 4) - t->origin_x;
    const int32_t sy = (y >> 4) - t->origin_y;
    const int32_t sz = (z >> 4) - t->origin_z;
    if (((uint32_t) sx >= (uint32_t) t->size_x) | ((uint32_t) sy >= (uint32_t) t->size_y)
            | ((uint32_t) sz >= (uint32_t) t->size_z)) {
        return nullptr;
    }
    const int32_t page = t->directory[((int64_t) sy * t->size_z + sz) * t->size_x + sx];
    return page < 0 ? nullptr : t->pages + (size_t) page * CP_PAGE_BYTES;
}

// The page every block of [x0, x1] x [y0, y1] x [z0, z1] lies in, when the box
// fits in one loaded section -- the usual case for an item, whose swept box
// spans a few blocks. Lets the scans below index the page directly instead of
// resolving the section again for every block.
inline const uint8_t* single_page(const cp_terrain* t, int32_t x0, int32_t y0, int32_t z0,
                                  int32_t x1, int32_t y1, int32_t z1) {
    if (((x0 ^ x1) | (y0 ^ y1) | (z0 ^ z1)) & ~15) {
        return nullptr;
    }
    return section_page(t, x0, y0, z0);
}

inline uint32_t page_cell(const uint8_t* page, int32_t x, int32_t y, int32_t z) {
    return page[(((uint32_t) y & 15u) << 8) | (((uint32_t) z & 15u) << 4) | ((uint32_t) x & 15u)];
}

// Whether any block of the box is solid or not modelled (COMPLEX).
inline bool box_blocked(const cp_terrain* t, int32_t x0, int32_t y0, int32_t z0, int32_t x1, int32_t y1, int32_t z1) {
    if (x0 > x1 || y0 > y1 || z0 > z1) {
        return false;
    }
    if (const uint8_t* page = single_page(t, x0, y0, z0, x1, y1, z1)) {
        for (int32_t cz = z0; cz <= z1; cz++) {
            for (int32_t cy = y0; cy <= y1; cy++) {
                for (int32_t cx = x0; cx <= x1; cx++) {
                    if (page_cell(page, cx, cy, cz) != CP_CELL_AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    for (int32_t cz = z0; cz <= z1; cz++) {
        for (int32_t cy = y0; cy <= y1; cy++) {
            for (int32_t cx = x0; cx <= x1; cx++) {
                if (terrain_cell(t, cx, cy, cz) != CP_CELL_AIR) {
                    return true;
                }
            }
        }
    }
    return false;
}

// Cells of the expanded box, gathered once per body so the three axis sweeps
// read a few hundred bytes of stack instead of re-walking the page directory.
// Indexed z-major, y, then x -- the order of vanilla's Cursor3D, so the flat
// index doubles as the cell's position in the collision-shape list.
struct Local {
    const cp_terrain* t;
    int32_t lo[3];
    int32_t n[3];
    int32_t last_rank;  // rank of the last solid cube in list order, NO_RANK if none
    uint8_t cell[MAX_CELLS];

    int32_t rank(const int32_t c[3]) const {
        return ((c[2] - lo[2]) * n[1] + (c[1] - lo[1])) * n[0] + (c[0] - lo[0]);
    }

    uint32_t at(const int32_t c[3]) const {
        const uint32_t lx = (uint32_t) (c[0] - lo[0]), ly = (uint32_t) (c[1] - lo[1]), lz = (uint32_t) (c[2] - lo[2]);
        if ((lx < (uint32_t) n[0]) & (ly < (uint32_t) n[1]) & (lz < (uint32_t) n[2])) {
            return cell[(lz * (uint32_t) n[1] + ly) * (uint32_t) n[0] + lx];
        }
        return terrain_cell(t, c[0], c[1], c[2]);
    }

    uint32_t at(int32_t x, int32_t y, int32_t z) const {
        const int32_t c[3] = {x, y, z};
        return at(c);
    }
};

// VoxelShape.collide for every full cube in the list, along axis `a`.
// Returns the clamped offset. Nearest layer first, so the first layer with a
// solid cube in the footprint decides (vanilla takes min/max over all cubes,
// which is the same value). The |d| < EPS snap reproduces Shapes.collide's
// check at the top of each list iteration: it fires when any shape follows
// the first cube that brought d within EPS of zero.
inline double sweep(const Local& L, int a, double d, const double lo[3], const double hi[3]) {
    if (std::fabs(d) < EPS) {
        return 0.0;
    }
    const int p = a == 0 ? 1 : 0;
    const int q = a == 2 ? 1 : 2;
    const int32_t p0 = ifloor(lo[p] + EPS), p1 = ifloor(hi[p] - EPS);
    const int32_t q0 = ifloor(lo[q] + EPS), q1 = ifloor(hi[q] - EPS);
    if ((p0 > p1) | (q0 > q1)) {
        return d;
    }
    const bool up = d > 0.0;
    int32_t c = up ? ifloor(hi[a] - EPS) + 1 : ifloor(lo[a] + EPS) - 1;
    const int32_t stop = up ? L.lo[a] + L.n[a] - 1 : L.lo[a];
    const int32_t step = up ? 1 : -1;
    for (; up ? c <= stop : c >= stop; c += step) {
        int32_t first = NO_RANK;
        int32_t cc[3];
        cc[a] = c;
        for (int32_t pp = p0; pp <= p1; pp++) {
            cc[p] = pp;
            for (int32_t qq = q0; qq <= q1; qq++) {
                cc[q] = qq;
                if (is_solid(L.at(cc))) {
                    const int32_t r = L.rank(cc);
                    first = r < first ? r : first;
                }
            }
        }
        if (first != NO_RANK) {
            const double face = up ? (double) c - hi[a] : (double) (c + 1) - lo[a];
            const double nd = up ? (face < d ? face : d) : (face > d ? face : d);
            if (std::fabs(nd) < EPS && first < L.last_rank) {
                return 0.0;
            }
            return nd;
        }
    }
    return d;
}

// Level.findSupportingBlock over the full cubes strictly intersecting the box:
// nearest block centre to the entity position, ties to the greatest BlockPos
// (Vec3i.compareTo: y, then z, then x). Returns false when none.
inline bool find_support(const Local& L, double bx0, double by0, double bz0, double bx1, double by1, double bz1,
                         double px, double py, double pz, int32_t out[3]) {
    const int32_t x0 = ifloor(bx0), x1 = iceil(bx1) - 1;
    const int32_t y0 = ifloor(by0), y1 = iceil(by1) - 1;
    const int32_t z0 = ifloor(bz0), z1 = iceil(bz1) - 1;
    bool found = false;
    double best = 0.0;
    for (int32_t z = z0; z <= z1; z++) {
        for (int32_t y = y0; y <= y1; y++) {
            for (int32_t x = x0; x <= x1; x++) {
                if (!is_solid(L.at(x, y, z))) {
                    continue;
                }
                const double dx = (double) x + 0.5 - px;
                const double dy = (double) y + 0.5 - py;
                const double dz = (double) z + 0.5 - pz;
                const double dist = dx * dx + dy * dy + dz * dz;
                bool greater = y != out[1] ? y > out[1] : (z != out[2] ? z > out[2] : x > out[0]);
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

// One body, one tick: everything between gravity and drag. Returns 1 when the
// body is deferred to the vanilla code path (nothing but its flags written).
inline int collide_body(const Columns& C, const cp_terrain* t, int32_t i, int32_t mode, Local& L) {
    const uint32_t flags = C.u[CP_FLAGS][i];
    const uint32_t phase = (flags >> CP_PHASE_SHIFT) & 3u;
    const uint32_t next_phase = ((phase + 1u) & 3u) << CP_PHASE_SHIFT;
    double* const fh = &C.f[CP_FACTOR_H][i];
    double* const fv = &C.f[CP_FACTOR_V][i];

    const double x = C.f[CP_POS_X][i], y = C.f[CP_POS_Y][i], z = C.f[CP_POS_Z][i];
    const double vx = C.f[CP_VEL_X][i], vy = C.f[CP_VEL_Y][i], vz = C.f[CP_VEL_Z][i];
    const double hw = C.f[CP_HALF_WIDTH][i], h = C.f[CP_HEIGHT][i];
    const bool on_ground = (flags & CP_FLAG_ON_GROUND) != 0;
    const bool may_change = (vx != 0.0) | (vy != 0.0) | (vz != 0.0) | (C.f[CP_GRAVITY][i] != 0.0);

    const uint32_t deferred_flags = (flags & ~(CP_FLAG_DEFERRED | CP_FLAG_MOVED | CP_FLAG_HELD | CP_PHASE_MASK))
            | CP_FLAG_DEFERRED | next_phase;

    // Non-finite or out-of-range state: vanilla handles it, nothing else is read.
    if (!(within(x, POS_LIMIT) & within(y, POS_LIMIT) & within(z, POS_LIMIT)
            & within(vx, VEL_LIMIT) & within(vy, VEL_LIMIT) & within(vz, VEL_LIMIT)
            & within(hw, SIZE_LIMIT) & within(h, SIZE_LIMIT))) {
        *fh = 1.0;
        *fv = 1.0;
        C.u[CP_FLAGS][i] = deferred_flags;
        return 1;
    }

    const double x0 = x - hw, x1 = x + hw, y0 = y, y1 = y + h, z0 = z - hw, z1 = z + hw;

    // ItemEntity: moves only if !onGround || horizontalDistanceSqr() > 1.0E-5F || (tickCount + id) % 4 == 0
    if ((flags & CP_FLAG_THROTTLE_RESTING) && on_ground && !(vx * vx + vz * vz > HOLD_SPEED_SQR) && phase != 0) {
        *fh = 1.0;
        *fv = 1.0;
        // noPhysics is decided before that test: a box overlapping a block pushes the
        // item out (moveTowardsClosestSpace), which may make it move this tick.
        if (box_blocked(t, ifloor(x0 + EPS), ifloor(y0 + EPS), ifloor(z0 + EPS),
                        iceil(x1 - EPS) - 1, iceil(y1 - EPS) - 1, iceil(z1 - EPS) - 1)) {
            C.u[CP_FLAGS][i] = deferred_flags;
            return 1;
        }
        C.u[CP_FLAGS][i] = (flags & ~(CP_FLAG_DEFERRED | CP_FLAG_MOVED | CP_PHASE_MASK))
                | CP_FLAG_HELD | next_phase | (may_change ? CP_FLAG_MOVED : 0u);
        return 0;
    }
    const double mx = vx, my = vy, mz = vz;

    // AABB.expandTowards
    const double e0[3] = {pick(mx < 0.0, x0 + mx, x0), pick(my < 0.0, y0 + my, y0), pick(mz < 0.0, z0 + mz, z0)};
    const double e1[3] = {pick(mx > 0.0, x1 + mx, x1), pick(my > 0.0, y1 + my, y1), pick(mz > 0.0, z1 + mz, z1)};
    // Each axis is checked on its own BEFORE the product: three spans of a few
    // million blocks multiply past 2^63 and wrap (2^22 * 2^21 * 2^21 = 2^64 -> 0),
    // which passed the MAX_CELLS test and let the gather write past L.cell.
    // (ifloor/iceil are clamped to +-2^30, so the difference fits in int32.)
    bool too_big = false;
    for (int a = 0; a < 3; a++) {
        L.lo[a] = ifloor(e0[a]);
        int32_t n = iceil(e1[a]) - L.lo[a];
        too_big |= n > MAX_CELLS;
        n = n & ~(n >> 31);                // max(n, 0)
        L.n[a] = n < MAX_CELLS ? n : MAX_CELLS;
    }
    // At most MAX_CELLS^3 here, so the product cannot overflow.
    const int64_t cells = (int64_t) L.n[0] * L.n[1] * L.n[2];
    if (too_big | (cells > MAX_CELLS)) {
        *fh = 1.0;
        *fv = 1.0;
        C.u[CP_FLAGS][i] = deferred_flags;
        return 1;
    }

    // Gather. Anything not modelled exactly anywhere in the swept region
    // (fluids, partial shapes, blocks with entityInside/stepOn effects,
    // unloaded sections) sends the body down the vanilla path.
    bool any_solid = false;
    bool any_complex = false;
    L.last_rank = NO_RANK;
    int32_t k = 0;
    const uint8_t* page = cells > 0
            ? single_page(t, L.lo[0], L.lo[1], L.lo[2], L.lo[0] + L.n[0] - 1, L.lo[1] + L.n[1] - 1, L.lo[2] + L.n[2] - 1)
            : nullptr;
    if (page != nullptr) {
        // One flat loop over the box (x fastest): a single exit branch
        // instead of one per row and layer, each mispredicted for the
        // 1-3 block spans items have.
        const uint32_t x0c = (uint32_t) L.lo[0] & 15u;
        uint32_t row = (((uint32_t) L.lo[1] & 15u) << 8) | (((uint32_t) L.lo[2] & 15u) << 4);
        int32_t cx = 0, cy = 0, cz = 0;
        for (; k < (int32_t) cells; k++) {
            const uint32_t c = page[row | (x0c + (uint32_t) cx)];
            L.cell[k] = (uint8_t) c;
            any_complex |= c == CP_CELL_COMPLEX;
            const bool solid = is_solid(c);
            any_solid |= solid;
            L.last_rank = solid ? k : L.last_rank;
            // advance x, carrying into y, then z (all within the one section)
            const bool wrap_x = ++cx == L.n[0];
            cx = wrap_x ? 0 : cx;
            cy += wrap_x;
            const bool wrap_y = cy == L.n[1];
            cy = wrap_y ? 0 : cy;
            cz += wrap_y;
            row = (((uint32_t) (L.lo[1] + cy) & 15u) << 8) | (((uint32_t) (L.lo[2] + cz) & 15u) << 4);
        }
    } else {
        // n[2] * n[1] * n[0] == cells <= MAX_CELLS iterations, like the loop above.
        for (int32_t cz = 0; cz < L.n[2]; cz++) {
            for (int32_t cy = 0; cy < L.n[1]; cy++) {
                for (int32_t cx = 0; cx < L.n[0]; cx++, k++) {
                    const uint32_t c = terrain_cell(t, L.lo[0] + cx, L.lo[1] + cy, L.lo[2] + cz);
                    L.cell[k] = (uint8_t) c;
                    any_complex |= c == CP_CELL_COMPLEX;
                    const bool solid = is_solid(c);
                    any_solid |= solid;
                    L.last_rank = solid ? k : L.last_rank;
                }
            }
        }
    }
    if (any_complex) {
        *fh = 1.0;
        *fv = 1.0;
        C.u[CP_FLAGS][i] = deferred_flags;
        return 1;
    }

    // ItemEntity: noPhysics = !level.noCollision(this, box.deflate(1.0E-7)) -> moveTowardsClosestSpace
    if (any_solid) {
        for (int32_t cz = ifloor(z0 + EPS); cz <= iceil(z1 - EPS) - 1; cz++) {
            for (int32_t cy = ifloor(y0 + EPS); cy <= iceil(y1 - EPS) - 1; cy++) {
                for (int32_t cx = ifloor(x0 + EPS); cx <= iceil(x1 - EPS) - 1; cx++) {
                    if (is_solid(L.at(cx, cy, cz))) {
                        *fh = 1.0;
                        *fv = 1.0;
                        C.u[CP_FLAGS][i] = deferred_flags;
                        return 1;
                    }
                }
            }
        }
    }

    // Entity.collideWithShapes: Y, then the larger horizontal axis last.
    double dx = mx, dy = my, dz = mz;
    if (any_solid && mx * mx + my * my + mz * mz != 0.0) {
        double lo[3] = {x0, y0, z0};
        double hi[3] = {x1, y1, z1};
        if (dy != 0.0) {
            dy = sweep(L, 1, dy, lo, hi);
            if (dy != 0.0) {
                lo[1] += dy;
                hi[1] += dy;
            }
        }
        const bool z_first = std::fabs(dx) < std::fabs(dz);
        if (z_first && dz != 0.0) {
            dz = sweep(L, 2, dz, lo, hi);
            if (dz != 0.0) {
                lo[2] += dz;
                hi[2] += dz;
            }
        }
        if (dx != 0.0) {
            dx = sweep(L, 0, dx, lo, hi);
            if (!z_first && dx != 0.0) {
                lo[0] += dx;
                hi[0] += dx;
            }
        }
        if (!z_first && dz != 0.0) {
            dz = sweep(L, 2, dz, lo, hi);
        }
    }

    // Entity.move
    const double moved_sqr = dx * dx + dy * dy + dz * dz;
    double nx = x, ny = y, nz = z;
    const bool apply = (mode & CP_RULE_SMALL_MOVES)
            ? (moved_sqr > MIN_MOVE_SQR || (mx * mx + my * my + mz * mz) - moved_sqr < MIN_MOVE_SQR)
            : moved_sqr > MIN_MOVE_SQR;
    if (apply) {
        nx = x + dx;
        ny = y + dy;
        nz = z + dz;
    }
    const bool hit_x = !(std::fabs(dx - mx) < MTH_EQUAL_EPS);
    const bool hit_z = !(std::fabs(dz - mz) < MTH_EQUAL_EPS);
    const bool v_coll = my != dy;
    const bool ground = v_coll && my < 0.0;

    // checkSupportingBlock / getOnPos, on the bounding box rebuilt by setPos
    const double bx0 = nx - hw, bx1 = nx + hw, by0 = ny, bz0 = nz - hw, bz1 = nz + hw;
    int32_t support[3] = {0, 0, 0};
    bool has_support = false;
    bool no_blocks = false;
    if (ground) {
        has_support = find_support(L, bx0, by0 - SUPPORT_EPS, bz0, bx1, by0, bz1, nx, ny, nz, support);
        if (!has_support && !(flags & CP_FLAG_GROUND_NO_BLOCKS)) {
            has_support = find_support(L, bx0 - dx, by0 - SUPPORT_EPS, bz0 - dz, bx1 - dx, by0, bz1 - dz,
                                       nx, ny, nz, support);
        }
        no_blocks = !has_support;
    }

    // getOnPosLegacy: the block whose updateEntityAfterFallOn / stepOn / fallOn
    // runs. Only the default implementation (vy = 0, nothing else) is modelled.
    if (v_coll) {
        const uint32_t c = has_support
                ? L.at(support[0], ifloor(ny - ON_POS_LEGACY), support[2])
                : L.at(ifloor(nx), ifloor(ny - ON_POS_LEGACY), ifloor(nz));
        if (c == CP_CELL_COMPLEX) {
            *fh = 1.0;
            *fv = 1.0;
            C.u[CP_FLAGS][i] = deferred_flags;
            return 1;
        }
    }

    // getBlockPosBelowThatAffectsMyMovement: its friction on the ground, and
    // its getSpeedFactor always -- Entity.move applies the speed factor of
    // the block below even mid-air (soul sand, honey: COMPLEX). Items look
    // almost a whole block down (getOnPos(0.999999F)).
    const double below_y = ny - C.f[CP_BELOW_OFFSET][i];
    const uint32_t below = has_support
            ? L.at(support[0], ifloor(below_y), support[2])
            : L.at(ifloor(nx), ifloor(below_y), ifloor(nz));
    if (below == CP_CELL_COMPLEX) {
        *fh = 1.0;
        *fv = 1.0;
        C.u[CP_FLAGS][i] = deferred_flags;
        return 1;
    }
    double factor_h = C.f[CP_DRAG_AIR_H][i];
    if (ground) {
        factor_h = (double) (t->friction[below] * (float) C.f[CP_GROUND_SCALE][i]);
    }

    // Commit
    C.f[CP_POS_X][i] = nx;
    C.f[CP_POS_Y][i] = ny;
    C.f[CP_POS_Z][i] = nz;
    if (hit_x | hit_z) {
        C.f[CP_VEL_X][i] = hit_x ? 0.0 : vx;
        C.f[CP_VEL_Z][i] = hit_z ? 0.0 : vz;
    }
    if (v_coll) {
        C.f[CP_VEL_Y][i] = vy * 0.0;  // Block.updateEntityAfterFallOn: multiply(1.0, 0.0, 1.0)
    }
    *fh = factor_h;
    *fv = C.f[CP_DRAG_V][i];
    const bool pos_changed = (nx != x) | (ny != y) | (nz != z);
    C.u[CP_FLAGS][i] = (flags & ~(CP_FLAG_OUT_MASK | CP_FLAG_ON_GROUND | CP_FLAG_GROUND_NO_BLOCKS | CP_PHASE_MASK))
            | next_phase
            | (ground ? CP_FLAG_ON_GROUND : 0u)
            | (no_blocks ? CP_FLAG_GROUND_NO_BLOCKS : 0u)
            | ((hit_x | hit_z) ? CP_FLAG_H_COLLISION : 0u)
            | (v_coll ? CP_FLAG_V_COLLISION : 0u)
            | ((pos_changed | may_change) ? CP_FLAG_MOVED : 0u);
    return 0;
}

inline int32_t step_impl(void* bodies, int32_t capacity, const cp_terrain* terrain,
                         int32_t begin, int32_t end, int32_t mode) {
    if (begin >= end) {
        return 0;
    }
    const Columns C = columns(bodies, capacity);
    // Padding lanes up to the next multiple of 16 are part of the slab, so
    // the SIMD passes run whole vectors with no scalar tail.
    int32_t lanes_end = (end + (CP_LANE_PAD - 1)) & ~(CP_LANE_PAD - 1);
    if (lanes_end > capacity) lanes_end = capacity;

    const bool fused = (mode & 0xFF) == CP_MODE_FUSED;
    if (!fused) {
        gravity_lanes(C.f[CP_VEL_Y], C.f[CP_GRAVITY], begin, lanes_end);
    }

    Local L;
    L.t = terrain;
    int32_t deferred = 0;
    uint32_t* const list = C.u[CP_DEFERRED_LIST];
    for (int32_t i = begin; i < end; i++) {
        if (collide_body(C, terrain, i, mode, L)) {
            list[begin + deferred++] = (uint32_t) i;
        }
    }
    // Padding lanes must not pick up stale factors from a body that was
    // since removed; 1.0 keeps them inert.
    for (int32_t i = end; i < lanes_end; i++) {
        C.f[CP_FACTOR_H][i] = 1.0;
        C.f[CP_FACTOR_V][i] = 1.0;
    }

    damp_lanes(C.f[CP_VEL_X], C.f[CP_VEL_Y], C.f[CP_VEL_Z], C.f[CP_FACTOR_H], C.f[CP_FACTOR_V],
               C.f[CP_GRAVITY], begin, lanes_end, fused);
    return deferred;
}
