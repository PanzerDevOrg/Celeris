package com.panzer.mods.celeris.framework.ticking;

public final class IdleTickFilter {

    private static final int IDLE_THRESHOLD_TICKS = 20;

    private int idleTickCount;
    private boolean previouslyActive;

    public boolean shouldTick(boolean isActiveThisTick) {
        if (isActiveThisTick) {
            idleTickCount = 0;
            previouslyActive = true;
            return true;
        }

        if (previouslyActive) {
            idleTickCount++;
            if (idleTickCount < IDLE_THRESHOLD_TICKS) {
                return true;
            }
            previouslyActive = false;
        }

        return false;
    }

    public void forceWake() {
        idleTickCount = 0;
        previouslyActive = true;
    }
}
