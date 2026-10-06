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
 * @param belowOffset       how far below the feet {@code getBlockPosBelowThatAffectsMyMovement} looks
 *                          (the block giving friction and speed factor): {@code 0.500001F} for
 *                          {@code Entity}, {@code 0.999999F} for {@code ItemEntity} and {@code ExperienceOrb}
 * @param throttleResting   ItemEntity rule: a resting body only moves every 4th tick
 */
public record BodyParams(double gravity, double dragAirHorizontal, double dragVertical, float groundScale,
                         float belowOffset, boolean throttleResting) {

    /** {@code Entity.getBlockPosBelowThatAffectsMyMovement}: {@code getOnPos(0.500001F)}. */
    public static final float ENTITY_BELOW_OFFSET = 0.500001F;

    /**
     * {@code ItemEntity}: gravity 0.04, {@code multiply(f, 0.98, f)} with {@code f = 0.98F} or
     * {@code friction * 0.98F}; the block below is {@code getOnPos(0.999999F)}.
     */
    public static final BodyParams ITEM = new BodyParams(0.04, (double) 0.98F, 0.98, 0.98F, 0.999999F, true);
}
