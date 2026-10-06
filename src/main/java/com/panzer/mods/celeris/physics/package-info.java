/**
 * Batch physics engine: simulates large numbers of simple entity bodies
 * (items, XP orbs, debris) in parallel, following vanilla's movement rules,
 * and hands back to vanilla whatever it does not model.
 *
 * <ul>
 *   <li>{@link com.panzer.mods.celeris.physics.BodyBatch}: dense SoA bodies
 *       ({@link com.panzer.mods.celeris.physics.BodyLayout}).</li>
 *   <li>{@link com.panzer.mods.celeris.physics.TerrainView}: paged voxel
 *       snapshot; only air and full cubes are simulated, everything else
 *       defers the body to vanilla for that tick.</li>
 *   <li>Engines, no JVM flags required: the native C++ kernel on one
 *       64-byte-aligned {@code Arena.ofShared()} slab when FFM is available,
 *       otherwise the pure-Java reference kernel on Java arrays. Both give the
 *       same results.</li>
 *   <li>{@link com.panzer.mods.celeris.physics.EntityBodyBridge}: dirty-bit
 *       sync between entities and bodies, no per-tick allocation.</li>
 * </ul>
 *
 * Entry point: {@link com.panzer.mods.celeris.physics.CelerisPhysics}.
 */
package com.panzer.mods.celeris.physics;
