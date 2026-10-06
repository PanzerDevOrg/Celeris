import com.panzer.mods.celeris.util.math.LocalBlockPos32;
import com.panzer.mods.celeris_example.lightwave.LightWaveStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness for ExampleSectionLightWave.step, isolated from BatchVectorApi's own
 * lane-boundary behavior (that's implicitly covered here too, since step()
 * runs the real SPECIES-length loop over the full 4096-element section --
 * this is not a toy-sized array that stays entirely in the scalar tail).
 */
@SuppressWarnings("unused")
class ExampleSectionLightWaveSmokeTest {

    @Test
    void singleLitSourcePropagatesOneStepToNeighbors() {
        int n = LightWaveStep.VOLUME;
        float[] front = new float[n];
        float[] current = new float[n];
        boolean[] opaque = new boolean[n];
        float[] nextLevels = new float[n];
        boolean[] advanced = new boolean[n];

        int sourceIndex = LocalBlockPos32.pack(8, 8, 8);
        front[sourceIndex] = 15f;
        current[sourceIndex] = 15f;

        int advancedCount = LightWaveStep.step(front, current, opaque, nextLevels, advanced);

        // Only the source position itself is on the wave-front this step,
        // so exactly one position (itself) is examined -- and it does NOT
        // advance, since 15-1=14 is not > its own current level of 15.
        // This test exists to pin that "a position cannot re-propagate
        // into itself" behavior, not to exercise neighbor expansion (that
        // is the caller's responsibility: building the next wave-front
        // from outAdvanced across all 6 neighbor offsets, driven by
        // BranchlessTables.stepX/Y/Z -- not this class's job).
        assertEquals(0, advancedCount);
        assertFalse(advanced[sourceIndex]);
    }

    @Test
    void neighborWithLowerCurrentLevelAdvancesWhenNotOpaque() {
        int n = LightWaveStep.VOLUME;
        float[] front = new float[n];
        float[] current = new float[n];
        boolean[] opaque = new boolean[n];
        float[] nextLevels = new float[n];
        boolean[] advanced = new boolean[n];

        int neighborIndex = LocalBlockPos32.pack(5, 5, 5);
        front[neighborIndex] = 10f; // wave-front proposes level 10 here
        current[neighborIndex] = 0f; // currently unlit

        int advancedCount = LightWaveStep.step(front, current, opaque, nextLevels, advanced);

        assertEquals(1, advancedCount);
        assertTrue(advanced[neighborIndex]);
        assertEquals(9f, nextLevels[neighborIndex]);
    }

    @Test
    void opaqueNeighborNeverAdvancesEvenWithHigherProposedLevel() {
        int n = LightWaveStep.VOLUME;
        float[] front = new float[n];
        float[] current = new float[n];
        boolean[] opaque = new boolean[n];
        float[] nextLevels = new float[n];
        boolean[] advanced = new boolean[n];

        int blockedIndex = LocalBlockPos32.pack(3, 3, 3);
        front[blockedIndex] = 15f;
        current[blockedIndex] = 0f;
        opaque[blockedIndex] = true;

        int advancedCount = LightWaveStep.step(front, current, opaque, nextLevels, advanced);

        assertEquals(0, advancedCount);
        assertFalse(advanced[blockedIndex]);
        assertEquals(0f, nextLevels[blockedIndex]);
    }

    @Test
    void positionAtLevelZeroFrontDoesNotAdvanceAnything() {
        int n = LightWaveStep.VOLUME;
        float[] front = new float[n];   // all zero -- nothing on the wave-front
        float[] current = new float[n];
        boolean[] opaque = new boolean[n];
        float[] nextLevels = new float[n];
        boolean[] advanced = new boolean[n];

        int advancedCount = LightWaveStep.step(front, current, opaque, nextLevels, advanced);

        assertEquals(0, advancedCount);
        for (int i = 0; i < n; i++) {
            assertFalse(advanced[i]);
            assertEquals(current[i], nextLevels[i]);
        }
    }

    @Test
    void alreadyBrighterCurrentLevelIsNotOverwrittenByWeakerFront() {
        int n = LightWaveStep.VOLUME;
        float[] front = new float[n];
        float[] current = new float[n];
        boolean[] opaque = new boolean[n];
        float[] nextLevels = new float[n];
        boolean[] advanced = new boolean[n];

        int index = LocalBlockPos32.pack(1, 1, 1);
        front[index] = 5f; // proposes 5 - 1 = 4
        current[index] = 12f; // already brighter from another source

        int advancedCount = LightWaveStep.step(front, current, opaque, nextLevels, advanced);

        assertEquals(0, advancedCount);
        assertFalse(advanced[index]);
        assertEquals(12f, nextLevels[index]);
    }
}
