// Shared includes for every translation unit. Deliberately no project code
// with external linkage beyond the C header: see cp_kernel.inc for why the
// ISA-specific units must not share inline functions or templates.
#ifndef CP_PRELUDE_HPP
#define CP_PRELUDE_HPP

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

#if defined(__SSE2__) || defined(_M_X64)
#  include <emmintrin.h>
#endif

#include "celeris_physics.h"

namespace cp {

using step_fn = int32_t (*)(void* bodies, int32_t capacity, const cp_terrain* terrain,
                            int32_t begin, int32_t end, int32_t mode);

namespace generic { int32_t step(void*, int32_t, const cp_terrain*, int32_t, int32_t, int32_t); }
#if defined(CP_HAVE_AVX2)
namespace avx2 { int32_t step(void*, int32_t, const cp_terrain*, int32_t, int32_t, int32_t); }
#endif
#if defined(CP_HAVE_AVX512)
namespace avx512 { int32_t step(void*, int32_t, const cp_terrain*, int32_t, int32_t, int32_t); }
#endif
#if defined(CP_HAVE_NEON)
namespace neon { int32_t step(void*, int32_t, const cp_terrain*, int32_t, int32_t, int32_t); }
#endif

}  // namespace cp

#endif
