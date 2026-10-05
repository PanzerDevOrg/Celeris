package com.panzer.mods.celeris.physics;

/**
 * Per-entity-type simulation constants. Create one per entity type at
 * startup and reuse it: {@link BodyBatch#add} copies the values into the
 * batch's columns, so nothing here is touched during a tick.
 *
 * @param gravity           subtracted from the vertical velocity before moving
 * @param dragAirHorizontal horizontal velocity multiplier while airborne
 * @param dragVertical      vertical velocity multiplier
 * @param groundScale       multiplied (as float, like vanilla) with the friction of the block below
 * @param throttleResting   ItemEntity rule: a resting body only moves every 4th tick
 */
public record BodyParams(double gravity, double dragAirHorizontal, double dragVertical, float groundScale,
                         boolean throttleResting) {

    /** {@code ItemEntity}: gravity 0.04, {@code multiply(f, 0.98, f)} with {@code f = 0.98F} or {@code friction * 0.98F}. */
    public static final BodyParams ITEM = new BodyParams(0.04, (double) 0.98F, 0.98, 0.98F, true);
}
