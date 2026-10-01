package com.panzer.mods.celeris_example.lightwave;


import com.panzer.mods.celeris.api.simd.VectorOperations;

import java.util.Arrays;

/**
 * Vectorized wave-front step for block-light inside a 16³ section.
 * See docs/simd-lightwave.md for why only the neighbor-evaluation is SIMD-friendly.
 */
public final class LightWaveStep {
    public static final int VOLUME = 16 * 16 * 16;

    public static int step(float[] front, float[] current, boolean[] opaque,
                           float[] outNext, boolean[] outAdvanced) {
        int n = VOLUME;

        System.arraycopy(front, 0, outNext, 0, n);
        VectorOperations.subtractInPlace(outNext, ONES, n);

        boolean[] wouldGain = new boolean[n];
        VectorOperations.greaterThanMask(outNext, current, wouldGain, n);

        int count = 0;
        for (int i = 0; i < n; i++) {
            boolean ok = wouldGain[i] && !opaque[i] && outNext[i] > 0f;
            outAdvanced[i] = ok;
            outNext[i] = ok ? outNext[i] : current[i];
            if (ok) count++;
        }
        return count;
    }

    private static final float[] ONES = Arrays.copyOf(
            new float[VOLUME], VOLUME);
    static { Arrays.fill(ONES, 1.0f); }
}
