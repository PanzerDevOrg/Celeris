// Baseline ISA (x86-64 SSE2 / any AArch64): plain loops. The compiler may
// auto-vectorise them; with -ffp-contract=off that cannot change results.
#include "cp_prelude.hpp"

namespace cp {
namespace generic {
namespace {

void gravity_lanes(double* vy, const double* g, int32_t begin, int32_t end) {
    for (int32_t i = begin; i < end; i++) {
        vy[i] = vy[i] - g[i];
    }
}

void damp_lanes(double* vx, double* vy, double* vz, const double* fh, const double* fv, const double* g,
                int32_t begin, int32_t end, bool fused) {
    if (fused) {
        for (int32_t i = begin; i < end; i++) {
            vx[i] = vx[i] * fh[i];
            vy[i] = std::fma(vy[i], fv[i], -g[i]);
            vz[i] = vz[i] * fh[i];
        }
    } else {
        for (int32_t i = begin; i < end; i++) {
            vx[i] = vx[i] * fh[i];
            vy[i] = vy[i] * fv[i];
            vz[i] = vz[i] * fh[i];
        }
    }
}

#include "cp_kernel.inc"
#include "cp_broadphase.inc"

}  // namespace

int32_t step(void* bodies, int32_t capacity, const cp_terrain* terrain, int32_t begin, int32_t end, int32_t mode) {
    return step_impl(bodies, capacity, terrain, begin, end, mode);
}

int32_t broadphase(void* bodies, int32_t capacity, int32_t count, int32_t* buckets, int32_t buckets_n,
                   double margin, int32_t* pairs, int32_t pair_capacity, int32_t flags) {
    return broadphase_impl(bodies, capacity, count, buckets, buckets_n, margin, pairs, pair_capacity, flags);
}

}  // namespace generic
}  // namespace cp
