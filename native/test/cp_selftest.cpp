// Native self-test, no JVM: scenario checks plus bit-exact agreement of every
// ISA the host CPU supports with the generic path on randomised scenes.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cmath>
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
    explicit Slab(int32_t capacity) : cap(capacity) {
        size_t bytes = (size_t) capacity * (8u * CP_F64_COLUMNS + 4u * CP_U32_COLUMNS);
        raw.assign(bytes + 64, 0);
        base = raw.data() + ((64 - ((uintptr_t) raw.data() & 63)) & 63);
    }
    double* f(int col) { return reinterpret_cast<double*>(base + (size_t) col * cap * 8); }
    uint32_t* u(int col) { return reinterpret_cast<uint32_t*>(base + (size_t) CP_F64_COLUMNS * cap * 8 + (size_t) col * cap * 4); }
    size_t bytes() const { return (size_t) cap * (8u * CP_F64_COLUMNS + 4u * CP_U32_COLUMNS); }
};

struct World {
    // 3x2x3 sections around the origin: x,z in [-16, 32), y in [0, 32)
    std::vector<int32_t> dir;
    std::vector<uint8_t> pages;
    float friction[256];
    cp_terrain t{};
    World() {
        dir.resize(3 * 2 * 3);
        pages.assign(dir.size() * CP_PAGE_BYTES, CP_CELL_AIR);
        for (size_t k = 0; k < dir.size(); k++) dir[k] = (int32_t) k;
        for (float& f : friction) f = 0.6f;
        friction[2] = 0.98f;  // ice
        t.directory = dir.data();
        t.pages = pages.data();
        t.friction = friction;
        t.origin_x = -1; t.origin_y = 0; t.origin_z = -1;
        t.size_x = 3; t.size_y = 2; t.size_z = 3;
    }
    void set(int x, int y, int z, uint8_t c) {
        int sx = (x >> 4) + 1, sy = y >> 4, sz = (z >> 4) + 1;
        int page = dir[(sy * 3 + sz) * 3 + sx];
        pages[(size_t) page * CP_PAGE_BYTES + ((y & 15) << 8 | (z & 15) << 4 | (x & 15))] = c;
    }
};

void item(Slab& s, int i, double x, double y, double z, double vx, double vy, double vz) {
    s.f(CP_POS_X)[i] = x; s.f(CP_POS_Y)[i] = y; s.f(CP_POS_Z)[i] = z;
    s.f(CP_VEL_X)[i] = vx; s.f(CP_VEL_Y)[i] = vy; s.f(CP_VEL_Z)[i] = vz;
    s.f(CP_HALF_WIDTH)[i] = (double) (0.25f / 2.0f);
    s.f(CP_HEIGHT)[i] = (double) 0.25f;
    s.f(CP_GRAVITY)[i] = 0.04;
    s.f(CP_DRAG_AIR_H)[i] = (double) 0.98f;
    s.f(CP_DRAG_V)[i] = 0.98;
    s.f(CP_GROUND_SCALE)[i] = (double) 0.98f;
    s.f(CP_BELOW_OFFSET)[i] = (double) 0.999999f;
    s.u(CP_FLAGS)[i] = CP_FLAG_THROTTLE_RESTING | ((uint32_t) (i & 3) << CP_PHASE_SHIFT);
}

void scenarios() {
    World w;
    for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++) w.set(x, 4, z, 1);  // stone floor, top at y=5
    w.set(3, 4, 3, 2);         // one ice block
    w.set(6, 5, 0, 1);         // a wall cube
    w.set(9, 5, 9, CP_CELL_COMPLEX);

    Slab s(16);
    item(s, 0, 0.5, 8.0, 0.5, 0, 0, 0);       // falls and lands
    item(s, 1, 5.7, 5.0, 0.5, 0.3, 0, 0);     // slides into the wall at x=6
    item(s, 2, 9.5, 5.0, 9.5, 0, 0, 0);       // next to a complex cell -> deferred
    item(s, 3, 3.5, 5.0, 3.5, 0.2, 0, 0);     // on ice
    s.u(CP_FLAGS)[1] |= CP_FLAG_ON_GROUND;
    s.u(CP_FLAGS)[3] |= CP_FLAG_ON_GROUND;

    for (int tick = 0; tick < 100; tick++) {
        int32_t d = cp_step(s.base, s.cap, &w.t, 0, 4, CP_MODE_VANILLA);
        CHECK(d == 1, "tick %d: expected 1 deferred, got %d", tick, d);
        CHECK(s.u(CP_DEFERRED_LIST)[0] == 2, "deferred list holds body 2");
    }
    CHECK(s.f(CP_POS_Y)[0] == 5.0, "item 0 rests on the floor top, y=%.17g", s.f(CP_POS_Y)[0]);
    CHECK(s.u(CP_FLAGS)[0] & CP_FLAG_ON_GROUND, "item 0 on ground");
    CHECK(s.f(CP_POS_X)[1] + s.f(CP_HALF_WIDTH)[1] <= 6.0 + 1e-7, "item 1 stopped by the wall, x=%.17g", s.f(CP_POS_X)[1]);
    CHECK(s.f(CP_VEL_X)[1] == 0.0, "item 1 lost x velocity on impact");
    CHECK(s.f(CP_POS_X)[3] > 3.5 + 0.2, "item 3 slid further on ice, x=%.17g", s.f(CP_POS_X)[3]);

    // Falling item, analytically: vanilla free fall for one tick.
    Slab f(16);
    item(f, 0, 0.5, 20.0, 0.5, 0, 0, 0);
    cp_step(f.base, f.cap, &w.t, 0, 1, CP_MODE_VANILLA);
    CHECK(f.f(CP_POS_Y)[0] == 20.0 - 0.04, "free fall y=%.17g", f.f(CP_POS_Y)[0]);
    CHECK(f.f(CP_VEL_Y)[0] == -0.04 * 0.98, "free fall vy=%.17g", f.f(CP_VEL_Y)[0]);
}

uint64_t rng_state = 0x9E3779B97F4A7C15ull;
double rnd() {
    rng_state ^= rng_state << 13; rng_state ^= rng_state >> 7; rng_state ^= rng_state << 17;
    return (double) (rng_state >> 11) * (1.0 / 9007199254740992.0);
}

void isa_parity() {
    World w;
    for (int x = -16; x < 32; x++) for (int y = 0; y < 32; y++) for (int z = -16; z < 32; z++) {
        double r = rnd();
        if (y < 3 || r < 0.15) w.set(x, y, z, r < 0.05 ? 2 : 1);
        else if (r < 0.17) w.set(x, y, z, CP_CELL_COMPLEX);
    }
    const int n = 1000;
    Slab ref(1008);
    for (int i = 0; i < n; i++) {
        item(ref, i, -14 + rnd() * 44, 3 + rnd() * 26, -14 + rnd() * 44,
             (rnd() - 0.5) * 0.8, (rnd() - 0.5) * 1.2, (rnd() - 0.5) * 0.8);
        if (rnd() < 0.5) ref.u(CP_FLAGS)[i] |= CP_FLAG_ON_GROUND;
        ref.f(CP_HALF_WIDTH)[i] = rnd() < 0.3 ? 0.3 : ref.f(CP_HALF_WIDTH)[i];
    }
    const int isas[] = {CP_ISA_AVX2, CP_ISA_AVX512, CP_ISA_NEON};
    const int modes[] = {CP_MODE_VANILLA, CP_MODE_FUSED, CP_MODE_VANILLA | CP_RULE_SMALL_MOVES};
    for (int mode : modes) {
        for (int isa : isas) {
            if (cp_force_isa(isa) != isa) continue;
            Slab a(1008), b(1008);
            std::memcpy(a.base, ref.base, ref.bytes());
            std::memcpy(b.base, ref.base, ref.bytes());
            for (int tick = 0; tick < 60; tick++) {
                cp_force_isa(CP_ISA_GENERIC);
                int32_t da = 0, db = 0;
                for (int c = 0; c < n; c += 256) da += cp_step(a.base, a.cap, &w.t, c, c + 256 < n ? c + 256 : n, mode);
                cp_force_isa(isa);
                for (int c = 0; c < n; c += 256) db += cp_step(b.base, b.cap, &w.t, c, c + 256 < n ? c + 256 : n, mode);
                CHECK(da == db, "isa %d mode %d tick %d: deferred %d vs %d", isa, mode, tick, da, db);
            }
            // Compare everything except the per-chunk deferred list ordering (identical too) -- whole slab.
            size_t cmp = (size_t) a.cap * 8u * CP_F64_COLUMNS + (size_t) a.cap * 4u * 2u;
            CHECK(std::memcmp(a.base, b.base, cmp) == 0, "isa %d mode %d differs from generic", isa, mode);
            std::printf("isa %d mode %d: bit-exact with generic over 60 ticks\n", isa, mode);
        }
    }
    cp_force_isa(CP_ISA_GENERIC);
}

void broadphase() {
    const int n = 3000;
    Slab s(3008);
    for (int i = 0; i < n; i++) {
        item(s, i, rnd() * 40, rnd() * 10, rnd() * 40, 0, 0, 0);
        s.f(CP_HALF_WIDTH)[i] = 0.1 + rnd() * 0.3;
        s.f(CP_HEIGHT)[i] = 0.2 + rnd() * 0.8;
    }
    const double m = 0.5;
    std::vector<int32_t> buckets(cp_bucket_count(s.cap) + 1);
    std::vector<int32_t> pairs(2 * 200000);
    int32_t got = cp_broadphase(s.base, s.cap, n, buckets.data(), (int32_t) buckets.size(), m, pairs.data(), 200000, 0);
    int64_t brute = 0;
    const double* x = s.f(CP_POS_X); const double* y = s.f(CP_POS_Y); const double* z = s.f(CP_POS_Z);
    const double* hw = s.f(CP_HALF_WIDTH); const double* h = s.f(CP_HEIGHT);
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
        bool o = (x[i] - hw[i] - m < x[j] + hw[j]) && (x[i] + hw[i] + m > x[j] - hw[j])
                && (y[i] - m < y[j] + h[j]) && (y[i] + h[i] + m > y[j])
                && (z[i] - hw[i] - m < z[j] + hw[j]) && (z[i] + hw[i] + m > z[j] - hw[j]);
        brute += o;
    }
    CHECK(got == brute, "broadphase found %d pairs, brute force %lld", got, (long long) brute);
    std::printf("broadphase: %d pairs (brute force agrees)\n", got);

    // Every ISA's broadphase: the same pairs in the same order as the generic one.
    const int32_t active = cp_active_isa();
    for (int isa : {CP_ISA_AVX2, CP_ISA_AVX512, CP_ISA_NEON}) {
        if (cp_force_isa(isa) != isa) continue;
        std::vector<int32_t> other(pairs.size());
        int32_t r = cp_broadphase(s.base, s.cap, n, buckets.data(), (int32_t) buckets.size(), m, other.data(), 200000, 0);
        CHECK(r == got && std::memcmp(other.data(), pairs.data(), sizeof(int32_t) * 2 * (size_t) got) == 0,
              "isa %d broadphase differs from generic (%d vs %d pairs)", isa, r, got);
    }
    cp_force_isa(active);

    // Stopping at capacity: the first `cap` pairs of the full list, then one more counted.
    for (int32_t cap : {0, 1, 100, got - 1, got, got + 5}) {
        std::vector<int32_t> cut(2 * (size_t) (cap > 0 ? cap : 1));
        int32_t r = cp_broadphase(s.base, s.cap, n, buckets.data(), (int32_t) buckets.size(), m,
                                  cut.data(), cap, CP_BROADPHASE_STOP_AT_CAPACITY);
        CHECK(r == (got < cap + 1 ? got : cap + 1), "stop at %d returned %d (total %d)", cap, r, got);
        CHECK(cap == 0 || std::memcmp(cut.data(), pairs.data(), sizeof(int32_t) * 2 * (size_t) (cap < got ? cap : got)) == 0,
              "stop at %d: pairs differ from the full list's prefix", cap);
    }
}

}  // namespace

int main() {
    CHECK(cp_abi_version() == CP_ABI_VERSION, "abi");
    std::printf("active isa: %d\n", cp_active_isa());
    const int32_t active = cp_active_isa();
    scenarios();
    isa_parity();
    broadphase();
    cp_force_isa(active);
    if (failures) {
        std::printf("%d failure(s)\n", failures);
        return 1;
    }
    std::printf("all native self-tests passed\n");
    return 0;
}
