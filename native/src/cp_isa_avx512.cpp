// AVX-512 F/DQ/VL: 8 x f64 lanes, one full cache line per load. Opt-in only
// (see best_isa() in cp_api.cpp for why AVX2 is the default).
#include <immintrin.h>

#include "cp_prelude.hpp"

namespace cp {
namespace avx512 {
namespace {

void gravity_lanes(double* vy, const double* g, int32_t begin, int32_t end) {
    for (int32_t i = begin; i < end; i += 8) {
        _mm512_store_pd(vy + i, _mm512_sub_pd(_mm512_load_pd(vy + i), _mm512_load_pd(g + i)));
    }
}

void damp_lanes(double* vx, double* vy, double* vz, const double* fh, const double* fv, const double* g,
                int32_t begin, int32_t end, bool fused) {
    if (fused) {
        for (int32_t i = begin; i < end; i += 8) {
            const __m512d h = _mm512_load_pd(fh + i);
            _mm512_store_pd(vx + i, _mm512_mul_pd(_mm512_load_pd(vx + i), h));
            _mm512_store_pd(vz + i, _mm512_mul_pd(_mm512_load_pd(vz + i), h));
            _mm512_store_pd(vy + i, _mm512_fmsub_pd(_mm512_load_pd(vy + i), _mm512_load_pd(fv + i),
                                                    _mm512_load_pd(g + i)));
        }
    } else {
        for (int32_t i = begin; i < end; i += 8) {
            const __m512d h = _mm512_load_pd(fh + i);
            _mm512_store_pd(vx + i, _mm512_mul_pd(_mm512_load_pd(vx + i), h));
            _mm512_store_pd(vz + i, _mm512_mul_pd(_mm512_load_pd(vz + i), h));
            _mm512_store_pd(vy + i, _mm512_mul_pd(_mm512_load_pd(vy + i), _mm512_load_pd(fv + i)));
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

}  // namespace avx512
}  // namespace cp
