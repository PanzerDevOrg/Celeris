package com.panzer.mods.celeris.physics;

import java.lang.foreign.MemorySegment;

/** A step/broadphase implementation over a {@link SegmentBodyBatch} slab (the native kernel). */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
interface PhysicsKernel {

    String name();

    /** One tick for bodies {@code [begin, end)}; {@code begin} is a multiple of {@link BodyLayout#LANE_PAD}. */
    int step(MemorySegment slab, int capacity, SegmentTerrain terrain, int begin, int end, int mode);

    int broadphase(MemorySegment slab, int capacity, int count, MemorySegment buckets, double margin,
                   MemorySegment pairs, int pairCapacity, boolean stopAtCapacity);
}
