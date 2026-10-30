// Step benchmark, no JVM: compares builds of the library on one scene.
//
//   cp_bench ab <isa> <unit> <ticks> <libA> <libB> [<libC> ...]
//   cp_bench count <lib> <isa> <unit> <ticks>
//
// `ab` loads each library with dlopen and steps the same scene through every one of them
// in a single process, alternating block by block (the order rotates, so none is always
// first). Per library it prints the median time per body and the median of the per-tick
// time ratio against the first library, with the quartiles of that ratio. Comparing
// separate processes on a shared VM is dominated by run-to-run noise of 5 to 15 percent;
// interleaving cancels the drift, and the same library listed twice shows what is noise
// (about +-1 percent). `count` steps without timing, for `valgrind --tool=callgrind`
// (instructions per body: run it with 0 ticks and with N and subtract).
//
// Scene: random 48x32x48 world and 100,000 items in batches of <unit> (as
// PhysicsBackendBenchmarkTest). isa is generic, avx2, avx512, neon or auto.
// Libraries must share this header's column layout (the ABI version may differ).
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <vector>

#include "celeris_physics.h"

namespace {

using step_fn = int32_t (*)(void*, int32_t, const cp_terrain*, int32_t, int32_t, int32_t);
using force_fn = int32_t (*)(int32_t);

struct Lib {
    step_fn step;
};

Lib open_lib(const char* path, int isa) {
    void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) {
        std::fprintf(stderr, "dlopen %s: %s\n", path, dlerror());
        std::exit(2);
    }
    Lib l;
    l.step = reinterpret_cast<step_fn>(dlsym(h, "cp_step"));
    force_fn force = reinterpret_cast<force_fn>(dlsym(h, "cp_force_isa"));
    if (l.step == nullptr || force == nullptr) {
        std::fprintf(stderr, "%s: no cp_step / cp_force_isa\n", path);
        std::exit(2);
    }
    if (isa >= 0 && force(isa) != isa) {
        std::fprintf(stderr, "%s: ISA %d not available on this CPU\n", path, isa);
        std::exit(3);
    }
    return l;
}

uint64_t rng_state = 0x9E3779B97F4A7C15ull;
double rnd() {
    rng_state ^= rng_state << 13; rng_state ^= rng_state >> 7; rng_state ^= rng_state << 17;
    return (double) (rng_state >> 11) * (1.0 / 9007199254740992.0);
}

struct World {
    std::vector<int32_t> dir;
    std::vector<uint8_t> pages;
    float friction[256];
    cp_terrain t{};
    World() {
        dir.resize(3 * 2 * 3);
        pages.assign(dir.size() * CP_PAGE_BYTES, CP_CELL_AIR);
        for (size_t k = 0; k < dir.size(); k++) dir[k] = (int32_t) k;
        for (float& f : friction) f = 0.6f;
        friction[2] = 0.98f;
        friction[3] = 0.8f;
        t.directory = dir.data(); t.pages = pages.data(); t.friction = friction;
        t.origin_x = -1; t.origin_y = 0; t.origin_z = -1;
        t.size_x = 3; t.size_y = 2; t.size_z = 3;
        for (int x = -16; x < 32; x++) for (int y = 0; y < 32; y++) for (int z = -16; z < 32; z++) {
            const double v = rnd();
            int c = 0;
            if (y < 3 || v < 0.15) c = v < 0.03 ? 2 : v < 0.05 ? 3 : 1;
            else if (v < 0.17) c = CP_CELL_COMPLEX;
            if (c == 0) continue;
            const int page = dir[((y >> 4) * 3 + ((z >> 4) + 1)) * 3 + ((x >> 4) + 1)];
            pages[(size_t) page * CP_PAGE_BYTES + ((y & 15) << 8 | (z & 15) << 4 | (x & 15))] = (uint8_t) c;
        }
    }
};

struct Slab {
    int32_t cap;
    std::vector<uint8_t> raw;
    uint8_t* base;
    explicit Slab(int32_t capacity) : cap(capacity) {
        raw.assign(bytes() + 64, 0);
        base = raw.data() + ((64 - ((uintptr_t) raw.data() & 63)) & 63);
    }
    size_t bytes() const { return (size_t) cap * (8u * CP_F64_COLUMNS + 4u * CP_U32_COLUMNS); }
    double* f(int col) { return reinterpret_cast<double*>(base + (size_t) col * cap * 8); }
    uint32_t* u(int col) {
        return reinterpret_cast<uint32_t*>(base + (size_t) CP_F64_COLUMNS * cap * 8 + (size_t) col * cap * 4);
    }
};

void fill(Slab& s, int n) {
    for (int i = 0; i < n; i++) {
        const float w = rnd() < 0.3 ? 0.6f : 0.25f, h = rnd() < 0.2 ? 1.8f : 0.25f;
        s.f(CP_POS_X)[i] = -14 + rnd() * 44;
        s.f(CP_POS_Y)[i] = 3 + rnd() * 26;
        s.f(CP_POS_Z)[i] = -14 + rnd() * 44;
        s.f(CP_VEL_X)[i] = (rnd() - 0.5) * 0.8;
        s.f(CP_VEL_Y)[i] = (rnd() - 0.5) * 1.2;
        s.f(CP_VEL_Z)[i] = (rnd() - 0.5) * 0.8;
        s.f(CP_HALF_WIDTH)[i] = (double) (w / 2.0f);
        s.f(CP_HEIGHT)[i] = (double) h;
        s.f(CP_GRAVITY)[i] = 0.04;
        s.f(CP_DRAG_AIR_H)[i] = (double) 0.98f;
        s.f(CP_DRAG_V)[i] = 0.98;
        s.f(CP_GROUND_SCALE)[i] = (double) 0.98f;
        s.f(CP_BELOW_OFFSET)[i] = (double) 0.999999f;
        s.u(CP_FLAGS)[i] = CP_FLAG_THROTTLE_RESTING | (rnd() < 0.5 ? CP_FLAG_ON_GROUND : 0u)
                | ((uint32_t) (i & 3) << CP_PHASE_SHIFT);
    }
}

double now_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1e9 + (double) ts.tv_nsec;
}

double quantile(std::vector<double> v, double p) {
    std::sort(v.begin(), v.end());
    const double pos = p * (double) (v.size() - 1);
    const size_t lo = (size_t) pos, hi = std::min(lo + 1, v.size() - 1);
    return v[lo] + (v[hi] - v[lo]) * (pos - (double) lo);
}

int parse_isa(const char* name) {
    if (!std::strcmp(name, "generic")) return CP_ISA_GENERIC;
    if (!std::strcmp(name, "avx2")) return CP_ISA_AVX2;
    if (!std::strcmp(name, "avx512")) return CP_ISA_AVX512;
    if (!std::strcmp(name, "neon")) return CP_ISA_NEON;
    return -1;  // auto: whatever the library picked at load
}

int usage() {
    std::fputs("usage: cp_bench ab <isa> <unit> <ticks> <libA> <libB> [<libC> ...]\n"
               "       cp_bench count <lib> <isa> <unit> <ticks>\n", stderr);
    return 1;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) return usage();
    const bool count = !std::strcmp(argv[1], "count");
    if (!count && std::strcmp(argv[1], "ab") != 0) return usage();
    const char* isa_name = argv[count ? 3 : 2];
    const int isa = parse_isa(isa_name);
    const int unit = std::atoi(argv[count ? 4 : 3]), ticks = std::atoi(argv[count ? 5 : 4]);
    if (unit < CP_LANE_PAD || unit % CP_LANE_PAD != 0 || ticks < 0) return usage();
    std::vector<const char*> paths;
    if (count) paths.push_back(argv[2]);
    else for (int i = 5; i < argc; i++) paths.push_back(argv[i]);
    if (!count && paths.size() < 2) return usage();

    const int total = 100000, batches = (total + unit - 1) / unit;
    const int block = unit >= 1024 ? 1 : 64;  // batches per timed block: keeps each timing well above the clock's cost
    World world;
    std::vector<Lib> libs;
    for (const char* p : paths) libs.push_back(open_lib(p, isa));
    const int n_libs = (int) libs.size();
    std::vector<std::vector<Slab>> slabs(n_libs);
    for (int b = 0; b < batches; b++) {
        Slab proto(unit);
        fill(proto, std::min(unit, total - b * unit));
        for (int l = 0; l < n_libs; l++) {
            slabs[l].emplace_back(unit);
            std::memcpy(slabs[l].back().base, proto.base, proto.bytes());
        }
    }
    long sink = 0;
    auto run = [&](int l, int from, int to) {
        for (int b = from; b < to; b++) {
            sink += libs[l].step(slabs[l][b].base, unit, &world.t, 0, std::min(unit, total - b * unit), CP_MODE_VANILLA);
        }
    };
    if (count) {
        for (int t = 0; t < ticks; t++) run(0, 0, batches);
        std::printf("%ld\n", sink);
        return 0;
    }
    for (int t = 0; t < 10; t++) for (int l = 0; l < n_libs; l++) run(l, 0, batches);
    std::vector<std::vector<double>> tick(n_libs, std::vector<double>((size_t) ticks, 0.0));
    int rot = 0;
    for (int t = 0; t < ticks; t++) {
        for (int from = 0; from < batches; from += block, rot++) {
            const int to = std::min(batches, from + block);
            for (int k = 0; k < n_libs; k++) {
                const int l = (k + rot) % n_libs;
                const double start = now_ns();
                run(l, from, to);
                tick[l][t] += now_ns() - start;
            }
        }
    }
    std::printf("unit %d, isa %s, %d ticks: ns per body (median) and per-tick time ratio against the first library\n",
                unit, isa_name, ticks);
    for (int l = 0; l < n_libs; l++) {
        std::vector<double> ratio((size_t) ticks);
        for (int t = 0; t < ticks; t++) ratio[t] = tick[l][t] / tick[0][t];
        std::printf("  %7.2f ns/body  %+6.2f%%  IQR [%+6.2f%%, %+6.2f%%]  %s\n", quantile(tick[l], 0.5) / total,
                    100 * (quantile(ratio, 0.5) - 1), 100 * (quantile(ratio, 0.25) - 1),
                    100 * (quantile(ratio, 0.75) - 1), paths[l]);
    }
    if (sink == -1) std::puts("");  // keeps the stepping observable
    return 0;
}
