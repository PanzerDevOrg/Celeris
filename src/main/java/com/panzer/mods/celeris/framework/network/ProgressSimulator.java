package com.panzer.mods.celeris.framework.network;

public final class ProgressSimulator {

    private long startGameTime = -1L;
    private long durationTicks;
    private boolean running;

    public void start(long currentGameTime, long durationInTicks) {
        this.startGameTime = currentGameTime;
        this.durationTicks = Math.max(1L, durationInTicks);
        this.running = true;
    }

    public void stop() {
        this.running = false;
        this.startGameTime = -1L;
    }

    public boolean isRunning() {
        return running;
    }

    public float progress(long currentGameTime) {
        if (!running || startGameTime < 0L) {
            return 0.0f;
        }
        long elapsed = currentGameTime - startGameTime;
        if (elapsed >= durationTicks) {
            return 1.0f;
        }
        if (elapsed <= 0L) {
            return 0.0f;
        }
        return (float) elapsed / (float) durationTicks;
    }
}
