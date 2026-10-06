/**
 * Core public surface of Celeris: what's active right now, and SIMD math.
 *
 * <ul>
 *   <li>{@link CelerisFeatures} -- "what's active
 *       right now" (FFM, SIMD, native compression), for a single condition
 *       in your own code.</li>
 *   <li>{@link com.panzer.mods.celeris.api.simd.VectorOperations} (in
 *       {@link com.panzer.mods.celeris.api.simd}) -- SIMD-or-scalar
 *       float-array math.</li>
 * </ul>
 *
 * <p>For block-entity helpers (async result handoff, progress tracking,
 * idle-tick filtering), see {@code com.panzer.mods.celeris.framework.*} --
 * equally public and stable, just organized by what it's for rather than
 * grouped in here.
 *
 * <p>Everything else (packages like {@code core}, {@code mixin}, {@code
 * pipeline}, {@code graph}) is internal. It's public Java-visibility only
 * because Minecraft/NeoForge mixins and cross-package wiring require it to
 * be -- it is not part of Celeris's compatibility contract and can change
 * between any two versions without notice.
 *
 * @since 0.1.0
 */
package com.panzer.mods.celeris.api;
