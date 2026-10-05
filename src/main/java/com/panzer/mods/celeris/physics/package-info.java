/**
 * Batch physics engine: simulates large numbers of simple entity bodies
 * (items, XP orbs, debris) off-heap, in parallel, with vanilla-identical
 * results where vanilla's own code would run.
 *
 * <ul>
 *   <li>{@link com.panzer.mods.celeris.physics.BodyBatch}: dense SoA bodies on
 *       one 64-byte-aligned {@code Arena.ofShared()} slab ({@link
 *       com.panzer.mods.celeris.physics.BodyLayout}).</li>
 *   <li>{@link com.panzer.mods.celeris.physics.TerrainView}: paged voxel
 *       snapshot; only air and full cubes are simulated, everything else
 *       defers the body to vanilla for that tick.</li>
 *   <li>Kernels: native C++ ({@code native/}, chosen per CPU), else the Java
 *       reference kernel, optionally with Vector API streaming passes.</li>
 *   <li>{@link com.panzer.mods.celeris.physics.EntityBodyBridge}: dirty-bit
 *       sync between entities and bodies, no per-tick allocation.</li>
 * </ul>
 *
 * Entry point: {@link com.panzer.mods.celeris.physics.CelerisPhysics}.
 */
package com.panzer.mods.celeris.physics;
