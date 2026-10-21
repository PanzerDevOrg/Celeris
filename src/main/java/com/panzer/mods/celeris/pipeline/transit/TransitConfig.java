package com.panzer.mods.celeris.pipeline.transit;

/**
 * Tuning of a {@link TransitSolver}.
 *
 * @param minSpacing   smallest distance between two items in a queue, in blocks; also bounds how many fit on a segment
 * @param defaultSpeed speed used by {@link TransitSolver#insert} when none is given, in blocks per tick
 */
public record TransitConfig(float minSpacing, float defaultSpeed) {

    public TransitConfig {
        if (!(minSpacing > 0f) || !Float.isFinite(minSpacing)) {
            throw new IllegalArgumentException("minSpacing must be positive and finite");
        }
        if (!(defaultSpeed > 0f) || !Float.isFinite(defaultSpeed)) {
            throw new IllegalArgumentException("defaultSpeed must be positive and finite");
        }
    }

    public static TransitConfig defaults() {
        return new TransitConfig(0.25f, 0.1f);
    }
}
