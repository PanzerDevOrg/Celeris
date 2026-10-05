// AVX2 + FMA: 4 x f64 lanes. Compiled with -mavx2 -mfma (/arch:AVX2) and only
// ever called after cp_api.cpp has confirmed CPU and OS support.
#include <immintrin.h>

#include "cp_prelude.hpp"

namespace cp {
namespace avx2 {
namespace {

void gravity_lanes(double* vy, const double* g, int32_t begin, int32_t end) {
    for (int32_t i = begin; i < end; i += 4) {
        _mm256_store_pd(vy + i, _mm256_sub_pd(_mm256_load_pd(vy + i), _mm256_load_pd(g + i)));
    }
}

void damp_lanes(double* vx, double* vy, double* vz, const double* fh, const double* fv, const double* g,
                int32_t begin, int32_t end, bool fused) {
    if (fused) {
        for (int32_t i = begin; i < end; i += 4) {
            const __m256d h = _mm256_load_pd(fh + i);
            _mm256_store_pd(vx + i, _mm256_mul_pd(_mm256_load_pd(vx + i), h));
            _mm256_store_pd(vz + i, _mm256_mul_pd(_mm256_load_pd(vz + i), h));
            // vy * fv - g with a single rounding
            _mm256_store_pd(vy + i, _mm256_fmsub_pd(_mm256_load_pd(vy + i), _mm256_load_pd(fv + i),
                                                    _mm256_load_pd(g + i)));
        }
    } else {
        for (int32_t i = begin; i < end; i += 4) {
            const __m256d h = _mm256_load_pd(fh + i);
            _mm256_store_pd(vx + i, _mm256_mul_pd(_mm256_load_pd(vx + i), h));
            _mm256_store_pd(vz + i, _mm256_mul_pd(_mm256_load_pd(vz + i), h));
            _mm256_store_pd(vy + i, _mm256_mul_pd(_mm256_load_pd(vy + i), _mm256_load_pd(fv + i)));
        }
    }
}

#include "cp_kernel.inc"

}  // namespace

int32_t step(void* bodies, int32_t capacity, const cp_terrain* terrain, int32_t begin, int32_t end, int32_t mode) {
    return step_impl(bodies, capacity, terrain, begin, end, mode);
}

}  // namespace avx2
}  // namespace cp
