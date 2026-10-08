// Entity-vs-entity broadphase: spatial hash + counting sort, O(n), no
// allocation. Baseline ISA only: the work is hashing and pointer chasing,
// which wide vectors do not speed up. Must stay in lock-step with
// JavaPhysicsKernel.broadphase (same hash, same visiting order), so
// results are reproducible across the native and Java paths.
#include <cstring>

#include "cp_prelude.hpp"

namespace {

constexpr double MIN_CELL = 0.0625;

// Same clamp as the kernel (and JavaPhysicsKernel.ifloor): no undefined cast
// for NaN, +-Inf or huge coordinates, and cell +-1 cannot overflow.
constexpr double INT_LO = -1073741824.0;
constexpr double INT_HI = 1073741823.0;
inline int32_t ifloor(double v) {
    const double lo = v > INT_LO ? v : INT_LO;  // NaN -> INT_LO
    return (int32_t) std::floor(lo < INT_HI ? lo : INT_HI);
}

inline uint32_t hash_cell(int32_t x, int32_t y, int32_t z) {
    return ((uint32_t) x * 73856093u) ^ ((uint32_t) y * 19349663u) ^ ((uint32_t) z * 83492791u);
}

}  // namespace

extern "C" {

CP_EXPORT int32_t cp_bucket_count(int32_t capacity) {
    int64_t want = (int64_t) capacity * 2;
    int64_t t = 16;
    while (t < want) t <<= 1;
    return (int32_t) t;
}

CP_EXPORT int32_t cp_broadphase(void* bodies, int32_t capacity, int32_t count, int32_t* buckets, int32_t buckets_len,
                                double margin, int32_t* pairs, int32_t pair_capacity, int32_t flags) {
    if (bodies == nullptr || ((uintptr_t) bodies & 63u) != 0 || capacity <= 0 || (capacity % CP_LANE_PAD) != 0
            || count < 0 || count > capacity || buckets == nullptr || buckets_len < cp_bucket_count(capacity) + 1
            || pair_capacity < 0 || (pairs == nullptr && pair_capacity > 0)) {
        return -1;
    }
    if (count < 2) {
        return 0;
    }
    char* base = static_cast<char*>(bodies);
    const size_t f64 = (size_t) capacity * 8u;
    const double* px = reinterpret_cast<const double*>(base + CP_POS_X * f64);
    const double* py = reinterpret_cast<const double*>(base + CP_POS_Y * f64);
    const double* pz = reinterpret_cast<const double*>(base + CP_POS_Z * f64);
    const double* hw = reinterpret_cast<const double*>(base + CP_HALF_WIDTH * f64);
    const double* hh = reinterpret_cast<const double*>(base + CP_HEIGHT * f64);
    uint32_t* u = reinterpret_cast<uint32_t*>(base + CP_F64_COLUMNS * f64);
    uint32_t* cell_hash = u + (size_t) CP_CELL_HASH * capacity;
    uint32_t* sorted = u + (size_t) CP_SORTED * capacity;

    // Cell edge >= largest extent + margin: two overlapping bodies then have
    // min corners less than one cell apart, so the 27-cell neighbourhood of
    // the min corner is enough.
    double max_ext = 0.0;
    for (int32_t i = 0; i < count; i++) {
        const double w = hw[i] + hw[i];
        const double e = w > hh[i] ? w : hh[i];
        max_ext = e > max_ext ? e : max_ext;
    }
    double cell = max_ext + margin;
    if (!(cell > 0.0)) {
        return 0;
    }
    if (cell < MIN_CELL) cell = MIN_CELL;
    const double inv = 1.0 / cell;

    const int32_t buckets_n = cp_bucket_count(capacity);
    const uint32_t mask = (uint32_t) buckets_n - 1u;
    for (int32_t i = 0; i < count; i++) {
        cell_hash[i] = hash_cell(ifloor((px[i] - hw[i]) * inv), ifloor(py[i] * inv), ifloor((pz[i] - hw[i]) * inv)) & mask;
    }

    // Counting sort; afterwards buckets[b] is the END of bucket b, and its
    // start is buckets[b - 1] (0 for b == 0).
    std::memset(buckets, 0, sizeof(int32_t) * (size_t) (buckets_n + 1));
    for (int32_t i = 0; i < count; i++) buckets[cell_hash[i]]++;
    int32_t run = 0;
    for (int32_t b = 0; b < buckets_n; b++) {
        const int32_t c = buckets[b];
        buckets[b] = run;
        run += c;
    }
    for (int32_t i = 0; i < count; i++) sorted[buckets[cell_hash[i]]++] = (uint32_t) i;

    // With STOP_AT_CAPACITY the scan ends at the (pair_capacity + 1)-th pair:
    // one past what fits, so the caller still learns the list was cut.
    const int64_t stop_at = (flags & CP_BROADPHASE_STOP_AT_CAPACITY) ? (int64_t) pair_capacity + 1 : INT64_MAX;
    int64_t found = 0;
    for (int32_t i = 0; i < count && found < stop_at; i++) {
        const double ax0 = px[i] - hw[i] - margin, ax1 = px[i] + hw[i] + margin;
        const double ay0 = py[i] - margin, ay1 = py[i] + hh[i] + margin;
        const double az0 = pz[i] - hw[i] - margin, az1 = pz[i] + hw[i] + margin;
        const int32_t cx = ifloor((px[i] - hw[i]) * inv), cy = ifloor(py[i] * inv), cz = ifloor((pz[i] - hw[i]) * inv);
        uint32_t seen[27];
        int32_t seen_n = 0;
        for (int32_t dz = -1; dz <= 1; dz++) {
            for (int32_t dy = -1; dy <= 1; dy++) {
                for (int32_t dx = -1; dx <= 1; dx++) {
                    const uint32_t b = hash_cell(cx + dx, cy + dy, cz + dz) & mask;
                    bool dup = false;
                    for (int32_t s = 0; s < seen_n; s++) dup |= seen[s] == b;
                    if (dup) continue;
                    seen[seen_n++] = b;
                    const int32_t lo = b == 0 ? 0 : buckets[b - 1];
                    const int32_t hi = buckets[b];
                    for (int32_t k = lo; k < hi; k++) {
                        const int32_t j = (int32_t) sorted[k];
                        if (j <= i) continue;
                        const double bx0 = px[j] - hw[j], bx1 = px[j] + hw[j];
                        const double bz0 = pz[j] - hw[j], bz1 = pz[j] + hw[j];
                        const bool overlap = (ax0 < bx1) & (ax1 > bx0) & (ay0 < py[j] + hh[j]) & (ay1 > py[j])
                                & (az0 < bz1) & (az1 > bz0);
                        if (overlap) {
                            if (found < pair_capacity) {
                                pairs[2 * found] = i;
                                pairs[2 * found + 1] = j;
                            }
                            if (++found == stop_at) {
                                return found > 0x7FFFFFFF ? 0x7FFFFFFF : (int32_t) found;
                            }
                        }
                    }
                }
            }
        }
    }
    return found > 0x7FFFFFFF ? 0x7FFFFFFF : (int32_t) found;
}

}  // extern "C"
