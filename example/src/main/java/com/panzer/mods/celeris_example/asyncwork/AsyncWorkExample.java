package com.panzer.mods.celeris_example.asyncwork;

import com.panzer.mods.celeris.framework.async.AsyncResultQueue;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kick expensive work to a background pool, drain results on the tick thread.
 * See docs/async-work.md for the full concurrency rationale.
 */
public final class AsyncWorkExample {
    private static final int QUEUE_CAP = 1 << 10;
    private static final int MAX_DRAIN = 128;

    private final AsyncResultQueue<PathResult> queue = new AsyncResultQueue<>(QUEUE_CAP);
    private final ExecutorService workers = Executors.newFixedThreadPool(2);

    public void findPathAsync(int entityId, long target) {
        workers.submit(() -> {
            PathResult result = computePath(entityId, target);
            if (!queue.offer(result)) {
                // Consumer is falling behind; tune QUEUE_CAP or backpressure here.
            }
        });
    }

    /** Call from ServerTickEvent.Post, once per tick. */
    public void drain(ServerLevel level) {
        queue.drainTo(result -> applyResult(level, result), MAX_DRAIN);
    }

    public void shutdown() {
        workers.shutdown();
        queue.close();
    }

    private PathResult computePath(int id, long target) { /* ... */ return null; }
    private void applyResult(ServerLevel level, PathResult r) { /* ... */ }

    private record PathResult(int entityId, long target) {}
}
