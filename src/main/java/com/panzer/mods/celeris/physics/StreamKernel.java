package com.panzer.mods.celeris.physics;

/**
 * Optional SIMD implementation of the two streaming passes of a step
 * (gravity before collision, drag after it), plugged under the Java kernel
 * when the native library is unavailable. Implemented in the {@code vector}
 * source set over {@code jdk.incubator.vector}; slabs are passed as {@code
 * Object} (a {@code MemorySegment}) so this interface never names an FFM or
 * incubator type -- see {@code CelerisVectorRuntime} for why {@code main}
 * must not.
 *
 * <p>{@code end} is already rounded up to a multiple of {@link
 * BodyLayout#LANE_PAD}: implementations process whole vectors only.
 */
public interface StreamKernel {

    String name();

    void gravity(Object slab, int capacity, int begin, int end);

    void damp(Object slab, int capacity, int begin, int end, boolean fused);
}
