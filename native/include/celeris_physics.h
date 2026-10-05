/*
 * celeris_physics -- batch body simulation kernel for Celeris.
 *
 * C ABI consumed from Java through the FFM Linker (see NativePhysicsKernel).
 * Every entry point is reentrant and allocation-free: all memory (bodies,
 * terrain, scratch, pair output) is owned by the Java side and passed in.
 *
 * Memory contract (mirrors com.panzer.mods.celeris.physics.BodyLayout):
 *   - One slab per batch, 64-byte aligned, Structure-of-Arrays.
 *   - `capacity` is a multiple of CP_LANE_PAD (16), so every column of 8-byte
 *     elements is a whole number of 128-byte blocks and every column of 4-byte
 *     elements a whole number of 64-byte cache lines. Kernels may therefore
 *     compute past `end` up to the next multiple of 16 (padding lanes) and never
 *     need a scalar tail loop.
 *   - Column k (f64) starts at  base + k * capacity * 8.
 *   - Column k (u32) starts at  base + CP_F64_COLUMNS * capacity * 8 + k * capacity * 4.
 *   - Chunks handed to cp_step on different threads must begin on a multiple
 *     of CP_LANE_PAD: every chunk boundary is then a cache-line boundary in
 *     every column, so parallel chunks never share a line (no false sharing).
 *
 * Floating point: built with -ffp-contract=off and without -ffast-math, so
 * CP_MODE_VANILLA results are bit-identical to the Java reference kernel
 * (ScalarPhysicsKernel) on every ISA. CP_MODE_FUSED uses FMA by definition.
 */
#ifndef CELERIS_PHYSICS_H
#define CELERIS_PHYSICS_H

#include <stdint.h>

#if defined(_WIN32)
#  define CP_EXPORT __declspec(dllexport)
#else
#  define CP_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Bumped on any change to the layout, flags or entry point signatures. */
#define CP_ABI_VERSION 1

#define CP_LANE_PAD 16

/* f64 columns */
enum {
    CP_POS_X = 0,
    CP_POS_Y,
    CP_POS_Z,
    CP_VEL_X,
    CP_VEL_Y,
    CP_VEL_Z,
    CP_HALF_WIDTH,      /* AABB = [x-hw, x+hw] x [y, y+h] x [z-hw, z+hw] (vanilla convention) */
    CP_HEIGHT,
    CP_GRAVITY,         /* subtracted from vel_y before moving */
    CP_DRAG_AIR_H,      /* horizontal velocity multiplier while airborne */
    CP_DRAG_V,          /* vertical velocity multiplier */
    CP_GROUND_SCALE,    /* holds a float: ground multiplier = (double)(friction_f32 * scale_f32) */
    CP_FACTOR_H,        /* scratch: written by collide, read by damp */
    CP_FACTOR_V,        /* scratch: written by collide, read by damp */
    CP_F64_COLUMNS
};

/* u32 columns */
enum {
    CP_FLAGS = 0,
    CP_DEFERRED_LIST,   /* per chunk: indices of deferred bodies, written from `begin` */
    CP_CELL_HASH,       /* broadphase scratch */
    CP_SORTED,          /* broadphase scratch */
    CP_U32_COLUMNS
};

/* Flag bits */
#define CP_FLAG_ON_GROUND        (1u << 0)  /* in/out */
#define CP_FLAG_H_COLLISION      (1u << 1)  /* out */
#define CP_FLAG_V_COLLISION      (1u << 2)  /* out */
#define CP_FLAG_DEFERRED         (1u << 3)  /* out: needs the vanilla code path this tick */
#define CP_FLAG_MOVED            (1u << 4)  /* out: position or velocity changed */
#define CP_FLAG_HELD             (1u << 5)  /* out: resting throttle skipped the move */
#define CP_FLAG_GROUND_NO_BLOCKS (1u << 6)  /* state: Entity.onGroundNoBlocks */
#define CP_FLAG_THROTTLE_RESTING (1u << 8)  /* in: ItemEntity rule, move resting bodies every 4th tick */
#define CP_FLAG_OUT_MASK         (CP_FLAG_H_COLLISION | CP_FLAG_V_COLLISION | CP_FLAG_DEFERRED \
                                  | CP_FLAG_MOVED | CP_FLAG_HELD)
#define CP_PHASE_SHIFT 24                     /* bits 24-25: (tickCount + id) & 3, advanced by cp_step */
#define CP_PHASE_MASK  (3u << CP_PHASE_SHIFT)

/* Terrain cell classes (one byte per block) */
#define CP_CELL_AIR     0x00u   /* empty, no side effects */
#define CP_CELL_COMPLEX 0xFFu   /* anything not modelled exactly: defers the body */
/* 0x01..0xFE: full solid cube; the value indexes cp_terrain.friction */

#define CP_PAGE_BYTES 4096      /* one 16x16x16 chunk section, index (y<<8)|(z<<4)|x */

/*
 * Paged voxel view. `directory` maps section coordinates inside the window to
 * a page index (-1: not loaded -> treated as COMPLEX). Written by Java
 * (SegmentTerrain) with a matching StructLayout; field order is ABI.
 */
typedef struct cp_terrain {
    const int32_t* directory;
    const uint8_t* pages;
    const float*   friction;     /* 256 entries, block friction as vanilla's float */
    int32_t origin_x, origin_y, origin_z;   /* window origin, in sections */
    int32_t size_x, size_y, size_z;         /* window size, in sections */
} cp_terrain;

enum {
    CP_MODE_VANILLA = 0,   /* Entity.move parity: no FMA, gravity -> move -> drag */
    CP_MODE_FUSED   = 1    /* free bodies: vel holds next tick's movement, drag+gravity in one FMA */
};

/* Rule bits OR-ed into `mode` (above the low byte) for Minecraft-version differences. */
#define CP_RULE_SMALL_MOVES (1 << 8)  /* Entity.move also applies movements the collision barely shortened */

enum {
    CP_ISA_GENERIC = 0,
    CP_ISA_AVX2    = 1,    /* AVX2 + FMA */
    CP_ISA_AVX512  = 2,    /* AVX-512 F/DQ/VL */
    CP_ISA_NEON    = 3     /* AArch64 Advanced SIMD (always present on AArch64) */
};

CP_EXPORT int32_t cp_abi_version(void);

/* Column counts, so the Java side can verify its layout matches this build. */
CP_EXPORT int32_t cp_layout_signature(void);

/* ISA selected at load from CPUID / HWCAP. */
CP_EXPORT int32_t cp_active_isa(void);

/* Forces an ISA (tests, diagnostics). Returns the ISA now active: a request
 * the CPU cannot run is ignored and the current one is returned. */
CP_EXPORT int32_t cp_force_isa(int32_t isa);

/*
 * Simulates bodies [begin, end) for one tick: gravity (SIMD) -> collide and
 * move against `terrain` (per body) -> drag/friction (SIMD), fused per call so
 * the chunk stays in L1/L2. Returns the number of deferred bodies; their
 * indices are written to the CP_DEFERRED_LIST column starting at `begin`.
 */
CP_EXPORT int32_t cp_step(void* bodies, int32_t capacity, const cp_terrain* terrain,
                          int32_t begin, int32_t end, int32_t mode);

/*
 * Broadphase over bodies [0, count): reports every pair (i < j) whose AABBs
 * overlap after inflating by `margin`, as consecutive (i, j) int32 pairs.
 * `buckets` holds cp_bucket_count(capacity) + 1 int32. Returns the total pair
 * count, which may exceed `pair_capacity` (only the first pair_capacity pairs
 * are written). Deterministic: same order as the Java reference.
 */
CP_EXPORT int32_t cp_bucket_count(int32_t capacity);
CP_EXPORT int32_t cp_broadphase(void* bodies, int32_t capacity, int32_t count, int32_t* buckets,
                                double margin, int32_t* pairs, int32_t pair_capacity);

#ifdef __cplusplus
}
#endif

#endif /* CELERIS_PHYSICS_H */
