// Entity-vs-entity broadphase: spatial hash + counting sort, O(n), no
// allocation. Included by every cp_isa_*.cpp after cp_kernel.inc (inside the
// same anonymous namespace, for the reasons given there), so each ISA gets its
// own copy: with the scan stopping at the pair buffer, the O(n) passes below
// (hashing with three floors per body, the bucket sweep) are most of a call,
// and the baseline build has no single-instruction floor. Must stay in
// lock-step with JavaPhysicsKernel.broadphase (same hash, same visiting
// order), so results are reproducible across the native and Java paths.
// Arguments are validated by cp_broadphase (cp_api.cpp).

constexpr double MIN_CELL = 0.0625;

inline uint32_t hash_cell(int32_t x, int32_t y, int32_t z) {
    return ((uint32_t) x * 73856093u) ^ ((uint32_t) y * 19349663u) ^ ((uint32_t) z * 83492791u);
}

inline int32_t broadphase_impl(void* bodies, int32_t capacity, int32_t count, int32_t* buckets, int32_t buckets_n,
                               double margin, int32_t* pairs, int32_t pair_capacity, int32_t flags) {
    const Columns C = columns(bodies, capacity);
    const double* px = C.f[CP_POS_X];
    const double* py = C.f[CP_POS_Y];
    const double* pz = C.f[CP_POS_Z];
    const double* hw = C.f[CP_HALF_WIDTH];
    const double* hh = C.f[CP_HEIGHT];
    uint32_t* cell_hash = C.u[CP_CELL_HASH];
    uint32_t* sorted = C.u[CP_SORTED];

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

    // Hash and count in one pass. Counting sort; afterwards buckets[b] is the
    // END of bucket b, and its start is buckets[b - 1] (0 for b == 0).
    const uint32_t mask = (uint32_t) buckets_n - 1u;
    std::memset(buckets, 0, sizeof(int32_t) * (size_t) (buckets_n + 1));
    for (int32_t i = 0; i < count; i++) {
        const uint32_t h = hash_cell(ifloor((px[i] - hw[i]) * inv), ifloor(py[i] * inv), ifloor((pz[i] - hw[i]) * inv)) & mask;
        cell_hash[i] = h;
        buckets[h]++;
    }
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
