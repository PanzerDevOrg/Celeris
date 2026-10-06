package com.panzer.mods.celeris.framework.capability;

import com.panzer.mods.celeris.util.math.StateBitmask;

import java.util.function.LongConsumer;

/**
 * Only fires capability invalidation on an actual structural block-state
 * change, instead of on every state change -- avoids re-invalidating (and
 * downstream re-lookup cost) for cosmetic changes that don't affect what a
 * capability sees.
 *
 * <p>Always invalidates once on the first call regardless of the bits
 * passed in, since there's no prior state to compare against yet.
 */
public final class CapabilityInvalidationFilter {

    @SuppressWarnings("FieldCanBeLocal")
    private long lastInvalidatedStateBits;
    private boolean initialized;

    /** Calls {@code invalidateCapabilitiesAt} with {@code packedPos} only if this is the first call or the state change was structural. */
    public void onStateChange(long previousStateBits, long newStateBits, long packedPos, LongConsumer invalidateCapabilitiesAt) {
        if (!initialized || StateBitmask.isStructuralChange(previousStateBits, newStateBits)) {
            invalidateCapabilitiesAt.accept(packedPos);
            lastInvalidatedStateBits = newStateBits;
            initialized = true;
        }
    }
}
