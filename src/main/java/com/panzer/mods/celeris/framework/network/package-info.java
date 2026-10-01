/**
 * Small helpers for machine-style block entities that show progress to the
 * player.
 *
 * <ul>
 *   <li>{@link com.panzer.mods.celeris.framework.network.ProgressSimulator} --
 *       a 0.0-1.0 progress value over a fixed tick duration, for a crafting
 *       bar, a charge-up animation, anything similar. Not itself a
 *       networking/sync mechanism -- call it on whichever side renders the
 *       bar.</li>
 *   <li>{@link com.panzer.mods.celeris.framework.network.ScreenTracker} --
 *       tracks whether any player currently has a menu open for a block, so
 *       you can skip sync/recompute work when nobody's looking.</li>
 * </ul>
 *
 * @since 0.1.0
 */
package com.panzer.mods.celeris.framework.network;
