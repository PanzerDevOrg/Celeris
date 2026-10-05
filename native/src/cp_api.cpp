// Exported C ABI: CPU detection, ISA dispatch, version checks. Compiled for
// the baseline ISA only, so it is safe to call on any CPU.
#include <atomic>

#include "cp_prelude.hpp"

#if defined(__x86_64__) || defined(_M_X64)
#  define CP_X86 1
#  if defined(_MSC_VER)
#    include <intrin.h>
#  else
#    include <cpuid.h>
#  endif
#endif

namespace {

#if defined(CP_X86)
void cpuid(uint32_t leaf, uint32_t sub, uint32_t r[4]) {
#  if defined(_MSC_VER)
    int t[4];
    __cpuidex(t, (int) leaf, (int) sub);
    for (int k = 0; k < 4; k++) r[k] = (uint32_t) t[k];
#  else
    __cpuid_count(leaf, sub, r[0], r[1], r[2], r[3]);
#  endif
}

uint64_t xgetbv0() {
#  if defined(_MSC_VER)
    return _xgetbv(0);
#  else
    uint32_t lo, hi;
    __asm__ volatile("xgetbv" : "=a"(lo), "=d"(hi) : "c"(0));
    return ((uint64_t) hi << 32) | lo;
#  endif
}

struct X86Caps {
    bool avx2 = false;     // AVX2 + FMA, OS saves YMM
    bool avx512 = false;   // F + DQ + VL, OS saves ZMM/opmask
};

X86Caps detect_x86() {
    X86Caps caps;
    uint32_t r[4];
    cpuid(0, 0, r);
    const uint32_t max_leaf = r[0];
    if (max_leaf < 7) return caps;
    cpuid(1, 0, r);
    const bool osxsave = (r[2] >> 27) & 1u, avx = (r[2] >> 28) & 1u, fma = (r[2] >> 12) & 1u;
    if (!osxsave || !avx) return caps;
    const uint64_t xcr0 = xgetbv0();
    cpuid(7, 0, r);
    const uint32_t ebx = r[1];
    caps.avx2 = fma && ((ebx >> 5) & 1u) && (xcr0 & 0x6u) == 0x6u;
    const bool f = (ebx >> 16) & 1u, dq = (ebx >> 17) & 1u, vl = (ebx >> 31) & 1u;
    caps.avx512 = caps.avx2 && f && dq && vl && (xcr0 & 0xE6u) == 0xE6u;
    return caps;
}
#endif

bool isa_supported(int32_t isa) {
    switch (isa) {
        case CP_ISA_GENERIC:
            return true;
#if defined(CP_X86) && defined(CP_HAVE_AVX2)
        case CP_ISA_AVX2:
            return detect_x86().avx2;
#endif
#if defined(CP_X86) && defined(CP_HAVE_AVX512)
        case CP_ISA_AVX512:
            return detect_x86().avx512;
#endif
#if defined(CP_HAVE_NEON)
        case CP_ISA_NEON:
            return true;
#endif
        default:
            return false;
    }
}

// AVX2 is the default even where AVX-512 exists: the streaming passes are
// a small share of a step (collision is gather-bound scalar code), and on
// the CPUs measured the 512-bit unit's frequency/power transitions cost more
// than the wider lanes win (AVX2 ~15% faster per step). AVX-512 stays
// available through cp_force_isa (-Dceleris.physics.isa=avx512).
int32_t best_isa() {
#if defined(CP_X86) && defined(CP_HAVE_AVX2)
    if (detect_x86().avx2) return CP_ISA_AVX2;
#endif
#if defined(CP_HAVE_NEON)
    return CP_ISA_NEON;
#endif
    return CP_ISA_GENERIC;
}

cp::step_fn step_for(int32_t isa) {
    switch (isa) {
#if defined(CP_HAVE_AVX2)
        case CP_ISA_AVX2: return &cp::avx2::step;
#endif
#if defined(CP_HAVE_AVX512)
        case CP_ISA_AVX512: return &cp::avx512::step;
#endif
#if defined(CP_HAVE_NEON)
        case CP_ISA_NEON: return &cp::neon::step;
#endif
        default: return &cp::generic::step;
    }
}

std::atomic<int32_t> g_isa{best_isa()};
std::atomic<cp::step_fn> g_step{step_for(best_isa())};

}  // namespace

extern "C" {

CP_EXPORT int32_t cp_abi_version(void) {
    return CP_ABI_VERSION;
}

CP_EXPORT int32_t cp_layout_signature(void) {
    return (CP_F64_COLUMNS << 16) | (CP_U32_COLUMNS << 8) | CP_LANE_PAD;
}

CP_EXPORT int32_t cp_active_isa(void) {
    return g_isa.load(std::memory_order_relaxed);
}

CP_EXPORT int32_t cp_force_isa(int32_t isa) {
    if (isa_supported(isa)) {
        g_step.store(step_for(isa), std::memory_order_relaxed);
        g_isa.store(isa, std::memory_order_relaxed);
    }
    return g_isa.load(std::memory_order_relaxed);
}

CP_EXPORT int32_t cp_step(void* bodies, int32_t capacity, const cp_terrain* terrain,
                          int32_t begin, int32_t end, int32_t mode) {
    // Misuse would corrupt the JVM heap silently; refuse instead. The Java
    // side never trips these, they guard hand-written callers.
    if (bodies == nullptr || terrain == nullptr || capacity <= 0 || (capacity % CP_LANE_PAD) != 0
            || begin < 0 || end > capacity || (begin % CP_LANE_PAD) != 0) {
        return -1;
    }
    return g_step.load(std::memory_order_relaxed)(bodies, capacity, terrain, begin, end, mode);
}

}  // extern "C"
