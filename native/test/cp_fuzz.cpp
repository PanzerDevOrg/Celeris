// Hostile-input test, no JVM: non-finite and huge body state, a misaligned
// slab, unloaded sections and malformed calls, on every ISA the CPU runs.
// Built and run by CI under ASan/UBSan (-fsanitize=address,undefined,
// float-cast-overflow), where any out-of-bounds access or undefined
// arithmetic fails the job; the checks below pin the documented outcomes.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#include "celeris_physics.h"

namespace {

int failures = 0;

#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL %s:%d: ", __FILE__, __LINE__); \
    std::printf(__VA_ARGS__); std::printf("\n"); failures++; } } while (0)

struct Slab {
    int32_t cap;
    std::vector<uint8_t> raw;
    uint8_t* base;
    Slab(int32_t capacity, size_t skew) : cap(capacity) {
        raw.assign(bytes() + 128, 0);
        base = raw.data() + ((64 - ((uintptr_t) raw.data() & 63)) & 63) + skew;
    }
    size_t bytes() const { return (size_t) cap * (8u * CP_F64_COLUMNS + 4u * CP_U32_COLUMNS); }
    double* f(int col) { return reinterpret_cast<double*>(base + (size_t) col * cap * 8); }
    uint32_t* u(int col) { return reinterpret_cast<uint32_t*>(base + (size_t) CP_F64_COLUMNS * cap * 8 + (size_t) col * cap * 4); }
};

// 3x2x3 sections around the origin (x, z in [-16, 32), y in [0, 32)); every
// fifth section is not loaded, and the loaded ones have a floor and some walls.
struct World {
    std::vector<int32_t> dir;
    std::vector<uint8_t> pages;
    float friction[256];
    cp_terrain t{};
    World() {
        dir.resize(18);
        pages.assign(dir.size() * CP_PAGE_BYTES, CP_CELL_AIR);
        for (size_t k = 0; k < dir.size(); k++) dir[k] = k % 5 == 0 ? -1 : (int32_t) k;
        for (size_t p = 0; p < dir.size(); p++) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) pages[p * CP_PAGE_BYTES + (2 << 8 | z << 4 | x)] = 1;
            pages[p * CP_PAGE_BYTES + (5 << 8 | 7 << 4 | 7)] = 1;
            pages[p * CP_PAGE_BYTES + (6 << 8 | 3 << 4 | 9)] = CP_CELL_COMPLEX;
        }
        for (float& f : friction) f = 0.6f;
        t.directory = dir.data();
        t.pages = pages.data();
        t.friction = friction;
        t.origin_x = -1; t.origin_y = 0; t.origin_z = -1;
        t.size_x = 3; t.size_y = 2; t.size_z = 3;
    }
};

const double INF = std::numeric_limits<double>::infinity();
const double NaN = std::numeric_limits<double>::quiet_NaN();
const double HOSTILE[] = {NaN, INF, -INF, 1e300, -1e300, 3e9, -3e9, 2147483648.0, -2147483649.0,
                          1e6, -1e6, 5e7, 2e9, -2e9, 1e8 + 1, 1e7 + 1};
const int FIELDS[] = {CP_POS_X, CP_POS_Y, CP_POS_Z, CP_VEL_X, CP_VEL_Y, CP_VEL_Z, CP_HALF_WIDTH, CP_HEIGHT};

void item(Slab& s, int i, double x, double y, double z, double vx, double vy, double vz) {
    s.f(CP_POS_X)[i] = x; s.f(CP_POS_Y)[i] = y; s.f(CP_POS_Z)[i] = z;
    s.f(CP_VEL_X)[i] = vx; s.f(CP_VEL_Y)[i] = vy; s.f(CP_VEL_Z)[i] = vz;
    s.f(CP_HALF_WIDTH)[i] = 0.125;
    s.f(CP_HEIGHT)[i] = 0.25;
    s.f(CP_GRAVITY)[i] = 0.04;
    s.f(CP_DRAG_AIR_H)[i] = (double) 0.98f;
    s.f(CP_DRAG_V)[i] = 0.98;
    s.f(CP_GROUND_SCALE)[i] = (double) 0.98f;
    s.f(CP_BELOW_OFFSET)[i] = (double) 0.999999f;
    s.u(CP_FLAGS)[i] = CP_FLAG_THROTTLE_RESTING | ((uint32_t) (i & 3) << CP_PHASE_SHIFT);
}

bool out_of_range(double v, int field) {
    const double limit = field <= CP_POS_Z ? 1e8 : field <= CP_VEL_Z ? 1e7 : 1e4;
    return !(std::fabs(v) <= limit);
}

// Every hostile value in every field, alone in a slab of ordinary items, on
// one ISA. A hostile body must come back deferred with its position, half
// width and height untouched; the others must be simulated as without it.
void hostile_bodies(int isa, const World& w) {
    const int n = 32;
    Slab clean(n, 0);
    for (int i = 0; i < n; i++) item(clean, i, -8.0 + (i % 8) * 4.1, 3.0 + i * 0.37, -8.0 + (i / 8) * 9.3,
                                     (i % 3 - 1) * 0.3, 0.1, ((i + 1) % 3 - 1) * 0.3);
    Slab ref(n, 0);
    std::memcpy(ref.base, clean.base, clean.bytes());
    cp_step(ref.base, ref.cap, &w.t, 0, n, CP_MODE_VANILLA);

    int k = 0;
    for (double v : HOSTILE) {
        for (int field : FIELDS) {
            const int victim = k++ % n;
            for (int mode : {CP_MODE_VANILLA, CP_MODE_FUSED}) {
                Slab s(n, 0);
                std::memcpy(s.base, clean.base, clean.bytes());
                s.f(field)[victim] = v;
                const double px = s.f(CP_POS_X)[victim], py = s.f(CP_POS_Y)[victim], pz = s.f(CP_POS_Z)[victim];
                const int32_t d = cp_step(s.base, s.cap, &w.t, 0, n, mode);
                CHECK(d >= 0 && d <= n, "isa %d mode %d: step returned %d", isa, mode, d);
                if (out_of_range(v, field)) {
                    CHECK(s.u(CP_FLAGS)[victim] & CP_FLAG_DEFERRED,
                          "isa %d mode %d: field %d = %g not deferred", isa, mode, field, v);
                    CHECK(std::memcmp(&s.f(CP_POS_X)[victim], &px, 8) == 0 && std::memcmp(&s.f(CP_POS_Y)[victim], &py, 8) == 0
                          && std::memcmp(&s.f(CP_POS_Z)[victim], &pz, 8) == 0,
                          "isa %d mode %d: deferred body %d moved", isa, mode, victim);
                }
                if (mode == CP_MODE_VANILLA) {
                    // The other bodies do not see the hostile one: same result as the clean run.
                    for (int i = 0; i < n; i++) {
                        if (i == victim) continue;
                        for (int col : {CP_POS_X, CP_POS_Y, CP_POS_Z, CP_VEL_X, CP_VEL_Y, CP_VEL_Z}) {
                            CHECK(std::memcmp(&s.f(col)[i], &ref.f(col)[i], 8) == 0,
                                  "isa %d: body %d column %d changed by hostile body %d", isa, i, col, victim);
                        }
                    }
                }
            }
        }
    }
    // Whole slabs of hostile values, every body at once, several ticks.
    for (double v : HOSTILE) {
        Slab s(n, 0);
        std::memcpy(s.base, clean.base, clean.bytes());
        for (int i = 0; i < n; i++) s.f(FIELDS[i % 8])[i] = v;
        for (int tick = 0; tick < 4; tick++) {
            const int32_t d = cp_step(s.base, s.cap, &w.t, 0, n, CP_MODE_VANILLA);
            CHECK(d >= 0 && d <= n, "isa %d: all-hostile step returned %d", isa, d);
        }
        std::vector<int32_t> buckets(cp_bucket_count(n) + 1);
        std::vector<int32_t> pairs(2 * 64);
        for (int32_t flags : {0, CP_BROADPHASE_STOP_AT_CAPACITY}) {
            const int32_t r = cp_broadphase(s.base, n, n, buckets.data(), (int32_t) buckets.size(), 0.5,
                                            pairs.data(), 64, flags);
            CHECK(r >= 0, "broadphase on hostile bodies returned %d", r);
        }
    }
}

// Huge velocities: spans of millions of blocks per axis (their product once
// wrapped to 0 and the gather overran its 512-byte buffer).
void huge_spans(int isa, const World& w) {
    const double speeds[] = {1e6, 4194302.0, 2097150.0, 1e7, 2e9};
    for (double a : speeds) for (double b : speeds) for (double c : speeds) {
        Slab s(16, 0);
        item(s, 0, 0.5, 8.0, 0.5, a, -b, c);
        const int32_t d = cp_step(s.base, s.cap, &w.t, 0, 1, CP_MODE_FUSED);
        CHECK(d == 1, "isa %d: velocity (%g, %g, %g) not deferred (%d)", isa, a, -b, c, d);
    }
}

void malformed_calls(const World& w) {
    Slab ok(16, 0), skewed(16, 8);
    for (int i = 0; i < 16; i++) { item(ok, i, 0.5 + i, 8, 0.5, 0, 0, 0); item(skewed, i, 0.5 + i, 8, 0.5, 0, 0, 0); }
    CHECK(cp_step(skewed.base, 16, &w.t, 0, 16, CP_MODE_VANILLA) == -1, "misaligned slab accepted");
    for (int which = 0; which < 3; which++) {
        cp_terrain bad = w.t;
        if (which == 0) bad.directory = nullptr;
        if (which == 1) bad.pages = nullptr;
        if (which == 2) bad.friction = nullptr;
        CHECK(cp_step(ok.base, 16, &bad, 0, 16, CP_MODE_VANILLA) == -1, "null terrain table %d accepted", which);
    }
    cp_terrain negative = w.t;
    negative.size_y = -2;
    CHECK(cp_step(ok.base, 16, &negative, 0, 16, CP_MODE_VANILLA) == -1, "negative window accepted");
    CHECK(cp_step(ok.base, 16, nullptr, 0, 16, CP_MODE_VANILLA) == -1, "null terrain accepted");
    CHECK(cp_step(ok.base, 20, &w.t, 0, 16, CP_MODE_VANILLA) == -1, "capacity 20 accepted");
    CHECK(cp_step(ok.base, 16, &w.t, 8, 16, CP_MODE_VANILLA) == -1, "unaligned chunk accepted");

    std::vector<int32_t> buckets(cp_bucket_count(16) + 1), pairs(32);
    const int32_t len = (int32_t) buckets.size();
    CHECK(cp_broadphase(skewed.base, 16, 16, buckets.data(), len, 0.5, pairs.data(), 16, 0) == -1, "misaligned broadphase");
    CHECK(cp_broadphase(ok.base, 16, 16, buckets.data(), len - 1, 0.5, pairs.data(), 16, 0) == -1, "short buckets");
    CHECK(cp_broadphase(ok.base, 16, 16, nullptr, len, 0.5, pairs.data(), 16, 0) == -1, "null buckets");
    CHECK(cp_broadphase(ok.base, 16, 16, buckets.data(), len, 0.5, nullptr, 16, 0) == -1, "null pairs");
    CHECK(cp_broadphase(ok.base, 16, 16, buckets.data(), len, 0.5, nullptr, 0, 0) >= 0, "null pairs, capacity 0");
    CHECK(cp_broadphase(ok.base, 16, 17, buckets.data(), len, 0.5, pairs.data(), 16, 0) == -1, "count > capacity");
    CHECK(cp_broadphase(ok.base, 16, -1, buckets.data(), len, 0.5, pairs.data(), 16, 0) == -1, "negative count");
    CHECK(cp_broadphase(ok.base, 16, 16, buckets.data(), len, 0.5, pairs.data(), -1, 0) == -1, "negative capacity");
    CHECK(cp_broadphase(ok.base, 24, 16, buckets.data(), len, 0.5, pairs.data(), 16, 0) == -1, "capacity 24");
}

}  // namespace

int main() {
    const int32_t active = cp_active_isa();
    const World w;
    int ran = 0;
    for (int isa : {CP_ISA_GENERIC, CP_ISA_AVX2, CP_ISA_AVX512, CP_ISA_NEON}) {
        if (cp_force_isa(isa) != isa) continue;
        hostile_bodies(isa, w);
        huge_spans(isa, w);
        std::printf("isa %d: hostile inputs handled\n", isa);
        ran++;
    }
    cp_force_isa(active);
    malformed_calls(w);
    CHECK(ran >= 1, "no ISA ran");
    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("fuzz: all checks passed on %d ISA(s)\n", ran);
    return 0;
}
