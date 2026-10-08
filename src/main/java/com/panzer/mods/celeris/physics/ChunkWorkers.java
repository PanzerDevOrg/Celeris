package com.panzer.mods.celeris.physics;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Minimal fork-join for chunked batch work with zero allocation per run:
 * persistent daemon threads, a shared chunk counter claimed with one atomic
 * increment per chunk (dynamic load balancing -- collision cost varies a lot
 * between chunks), and the calling thread working alongside. Unlike a
 * {@code ForkJoinPool}, submitting a run creates no task objects.
 *
 * <p>The claim counter carries the run's generation in its high 32 bits, so
 * a worker still finishing the previous run can never claim a chunk of the
 * next one with a stale task. The claim and done counters live 64 bytes
 * apart in one {@link AtomicLongArray}, so they never share a cache line.
 *
 * <p>One run at a time: the counters, task and generation belong to the run in
 * progress, so a second {@link #run} from another thread, or from inside a
 * chunk task, is refused with an {@link IllegalStateException} instead of
 * corrupting it. Callers that share an instance and can fall back to doing the
 * work themselves use {@link #tryRun}.
 */
public final class ChunkWorkers implements AutoCloseable {

    /** Work for one chunk index. Implement it once (e.g. on the batch); never capture per call. */
    @FunctionalInterface
    public interface ChunkTask {
        void run(int chunk);
    }

    // Each counter on its own cache line, away from the array header too.
    private static final int NEXT = 8;
    private static final int DONE = 16;
    /** How long a worker keeps polling for the next run before it parks (a step and a broadphase, or several levels, per tick). */
    private static final long SPIN_NANOS = Long.getLong("celeris.physics.workerSpinNanos", 30_000L);

    private final Thread[] threads;
    private final AtomicLongArray counters = new AtomicLongArray(24);
    private volatile ChunkTask task;
    private volatile int chunks;
    private volatile int generation;
    private volatile Throwable failure;
    private volatile boolean closed;
    /** 1 while a run (including a single-threaded one) is in progress; claimed by CAS. */
    @SuppressWarnings("unused")
    private volatile int running;

    private static final VarHandle RUNNING;

    static {
        try {
            RUNNING = MethodHandles.lookup().findVarHandle(ChunkWorkers.class, "running", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public ChunkWorkers(int threadCount) {
        threads = new Thread[Math.max(0, threadCount)];
        for (int i = 0; i < threads.length; i++) {
            Thread t = new Thread(this::workerLoop, "Celeris Physics Worker #" + i);
            t.setDaemon(true);
            threads[i] = t;
            t.start();
        }
    }

    public int threadCount() {
        return threads.length;
    }

    /**
     * Runs {@code task} for every chunk in {@code [0, chunkCount)} and returns
     * when all are done.
     *
     * @throws IllegalStateException if another run is in progress: called
     *         concurrently from another thread, or reentrantly from a chunk task
     */
    public void run(ChunkTask task, int chunkCount) {
        if (!tryRun(task, chunkCount)) {
            throw new IllegalStateException("ChunkWorkers.run called while another run is in progress"
                    + " (concurrently or from inside a chunk task)");
        }
    }

    /**
     * {@link #run}, unless another run is in progress: then nothing runs and this
     * returns {@code false}, so the caller can do the chunks itself.
     */
    public boolean tryRun(ChunkTask task, int chunkCount) {
        if (chunkCount <= 0) {
            return true;
        }
        // One CAS and one release store per run: the guard costs nothing measurable
        // next to waking workers and stepping thousands of bodies.
        if (!RUNNING.compareAndSet(this, 0, 1)) {
            return false;
        }
        try {
            runExclusive(task, chunkCount);
        } finally {
            RUNNING.setRelease(this, 0);
        }
        return true;
    }

    private void runExclusive(ChunkTask task, int chunkCount) {
        if (threads.length == 0 || chunkCount == 1) {
            for (int c = 0; c < chunkCount; c++) {
                task.run(c);
            }
            return;
        }
        failure = null;
        int gen = generation + 1;
        // Retag the claim counter BEFORE publishing the new task: a worker
        // that read the previous generation may already be about to pick up
        // the new task/chunks, and must then find a tag it does not match
        // rather than the previous run's exhausted counter.
        counters.set(NEXT, (long) gen << 32);
        counters.set(DONE, 0);
        this.task = task;
        this.chunks = chunkCount;
        generation = gen; // volatile write publishes task/chunks/counters
        // The calling thread takes chunks too: wake no more workers than there
        // are other chunks for (a parked worker costs a futex wake, a spinning
        // one notices the new generation by itself).
        for (int i = 0, n = Math.min(threads.length, chunkCount - 1); i < n; i++) {
            LockSupport.unpark(threads[i]);
        }
        drain(task, chunkCount, gen);
        int spins = 0;
        while (counters.get(DONE) < chunkCount) {
            if (++spins < 1 << 12) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
        this.task = null;
        Throwable t = failure;
        if (t != null) {
            throw new IllegalStateException("Celeris physics worker failed", t);
        }
    }

    private void drain(ChunkTask t, int n, int gen) {
        while (true) {
            long v = counters.get(NEXT);
            int c = (int) v;
            if ((int) (v >>> 32) != gen || c >= n) {
                return;
            }
            if (!counters.compareAndSet(NEXT, v, v + 1)) {
                continue;
            }
            try {
                t.run(c);
            } catch (Throwable e) {
                failure = e;
            } finally {
                counters.incrementAndGet(DONE);
            }
        }
    }

    private void workerLoop() {
        int seen = 0;
        while (!closed) {
            int gen = generation;
            if (gen == seen) {
                // Poll briefly first: back-to-back runs then start without a wake-up.
                long until = System.nanoTime() + SPIN_NANOS;
                while ((gen = generation) == seen && !closed && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                if (gen == seen) {
                    LockSupport.park(this);
                    continue;
                }
            }
            seen = gen;
            ChunkTask t = task;
            int n = chunks;
            if (t != null) {
                drain(t, n, gen);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Thread t : threads) {
            LockSupport.unpark(t);
        }
    }
}
