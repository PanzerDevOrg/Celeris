// AArch64 Advanced SIMD: 2 x f64 lanes, unrolled x2 to keep both FP pipes busy.
#include <arm_neon.h>

#include "cp_prelude.hpp"

namespace cp {
namespace neon {
namespace {

void gravity_lanes(double* vy, const double* g, int32_t begin, int32_t end) {
    for (int32_t i = begin; i < end; i += 4) {
        vst1q_f64(vy + i, vsubq_f64(vld1q_f64(vy + i), vld1q_f64(g + i)));
        vst1q_f64(vy + i + 2, vsubq_f64(vld1q_f64(vy + i + 2), vld1q_f64(g + i + 2)));
    }
}

void damp_lanes(double* vx, double* vy, double* vz, const double* fh, const double* fv, const double* g,
                int32_t begin, int32_t end, bool fused) {
    for (int32_t i = begin; i < end; i += 2) {
        const float64x2_t h = vld1q_f64(fh + i);
        vst1q_f64(vx + i, vmulq_f64(vld1q_f64(vx + i), h));
        vst1q_f64(vz + i, vmulq_f64(vld1q_f64(vz + i), h));
        const float64x2_t y = vld1q_f64(vy + i);
        const float64x2_t v = vld1q_f64(fv + i);
        // fused: -g + vy * fv with a single rounding
        vst1q_f64(vy + i, fused ? vfmaq_f64(vnegq_f64(vld1q_f64(g + i)), y, v) : vmulq_f64(y, v));
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

}  // namespace neon
}  // namespace cp
