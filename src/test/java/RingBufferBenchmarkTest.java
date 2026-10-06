import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@SuppressWarnings("unused")
class RingBufferBenchmarkTest {

    private static final int PRODUCERS = 4;
    private static final int ITEMS_PER_PRODUCER = 200_000;
    private static final int WARMUP_TRIALS = 2;
    private static final int MEASURED_TRIALS = 5;
    private static final int RING_CAPACITY = 1 << 12; // must stay power of two
    // Bumped from 1<<12 (4096) to 1<<16 (65536). At 4096 the ring saturates
    // during the bursty windows of a 4-producer / 1-consumer workload even
    // with batched consumption, which is what produced the wide variance
    // between trials (6.9M-17.5M ops/s in the same run). More headroom
    // means producers hit tryPublish()==false far less often.
    private static final int RING_CAPACITY_TUNED = 1 << 16;
    private static final int CONSUME_BATCH = 64;

    //* MpscRingBuffer
    // Spin policy for the producer side: pure Thread.onSpinWait() is only
    // appropriate for waits expected to last a handful of cycles. If the
    // ring stays full for longer than that (a real possibility even with
    // more headroom, under a true burst), spinning past this point just
    // burns cycles the consumer needs. After SPIN_LIMIT failed attempts,
    // fall back to Thread.yield() to give the scheduler a chance to run the
    // consumer instead.
    private static final int SPIN_LIMIT = 256;

    @SuppressWarnings("SameParameterValue")
    private static void publishWithBackoff(MpscRingBuffer ring, long value) {
        int spins = 0;
        while (!ring.tryPublish(value)) {
            if (spins < SPIN_LIMIT) {
                Thread.onSpinWait();
                spins++;
            } else {
                Thread.yield();
            }
        }
    }

    @Test
    void compareThroughputAcrossContenders() {
        List<Result> results = new ArrayList<>();

        results.add(benchmark("MpscRingBuffer (off-heap, lock-free)", this::runRingBufferTrial));
        results.add(benchmark("ArrayBlockingQueue (heap, lock-based, bounded)", this::runArrayBlockingQueueTrial));
        results.add(benchmark("LinkedBlockingQueue (heap, lock-based, bounded)", this::runLinkedBlockingQueueTrial));
        results.add(benchmark("ConcurrentLinkedQueue (heap, lock-free, UNBOUNDED)", this::runConcurrentLinkedQueueTrial));

        System.out.println();
        System.out.println("=== MPSC throughput comparison ===");
        System.out.printf("%-70s %14s %14s %14s%n", "Contender", "median ops/s", "min ops/s", "max ops/s");
        for (Result r : results) {
            System.out.printf("%-70s %,14.0f %,14.0f %,14.0f%n", r.label, r.medianOpsPerSec, r.minOpsPerSec, r.maxOpsPerSec);
        }
        System.out.println();
        // Intentionally no assertions on throughput numbers, this test is a
        // reporting tool, not a correctness gate. Correctness is covered by
        // RingBufferSmokeTest.
    }

    private Result benchmark(String label, LongSupplier trialRunner) {
        for (int i = 0; i < WARMUP_TRIALS; i++) {
            trialRunner.getAsLong();
        }

        List<Long> opsPerSec = new ArrayList<>(MEASURED_TRIALS);
        for (int i = 0; i < MEASURED_TRIALS; i++) {
            opsPerSec.add(trialRunner.getAsLong());
        }
        Collections.sort(opsPerSec);

        long min = opsPerSec.getFirst();
        long max = opsPerSec.getLast();
        long median = opsPerSec.get(opsPerSec.size() / 2);

        return new Result(label, median, min, max);
    }

    private long runRingBufferTrial() {
        try (MpscRingBuffer ring = new MpscRingBuffer(RING_CAPACITY_TUNED, Long.BYTES)) {
            AtomicLong consumed = new AtomicLong();
            CountDownLatch startLatch = new CountDownLatch(1);

            Thread[] producers = new Thread[PRODUCERS];
            for (int p = 0; p < PRODUCERS; p++) {
                producers[p] = new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                            publishWithBackoff(ring, 1L);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            long expectedTotal = (long) PRODUCERS * ITEMS_PER_PRODUCER;
            Thread consumer = new Thread(() -> {
                long[] out = new long[CONSUME_BATCH];
                while (consumed.get() < expectedTotal) {
                    int drained = ring.tryConsumeBatch(out, 0, CONSUME_BATCH);
                    if (drained > 0) {
                        consumed.addAndGet(drained);
                    } else {
                        Thread.onSpinWait();
                    }
                }
            });

            return timeTrial(startLatch, producers, consumer, expectedTotal, consumed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------
    // ArrayBlockingQueue
    // ---------------------------------------------------------------

    private long runArrayBlockingQueueTrial() {
        ArrayBlockingQueue<Long> queue = new ArrayBlockingQueue<>(RING_CAPACITY);
        AtomicLong consumed = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        queue.put(1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long expectedTotal = (long) PRODUCERS * ITEMS_PER_PRODUCER;
        Thread consumer = new Thread(() -> {
            try {
                while (consumed.get() < expectedTotal) {
                    queue.take();
                    consumed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        return timeTrial(startLatch, producers, consumer, expectedTotal, consumed);
    }

    // ---------------------------------------------------------------
    // LinkedBlockingQueue
    // ---------------------------------------------------------------

    private long runLinkedBlockingQueueTrial() {
        LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>(RING_CAPACITY);
        AtomicLong consumed = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        queue.put(1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long expectedTotal = (long) PRODUCERS * ITEMS_PER_PRODUCER;
        Thread consumer = new Thread(() -> {
            try {
                while (consumed.get() < expectedTotal) {
                    queue.take();
                    consumed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        return timeTrial(startLatch, producers, consumer, expectedTotal, consumed);
    }

    // ---------------------------------------------------------------
    // ConcurrentLinkedQueue (unbounded -- best-case baseline)
    // ---------------------------------------------------------------

    private long runConcurrentLinkedQueueTrial() {
        ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
        AtomicLong consumed = new AtomicLong();
        CountDownLatch startLatch = new CountDownLatch(1);

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            producers[p] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITEMS_PER_PRODUCER; i++) {
                        queue.offer(1L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        long expectedTotal = (long) PRODUCERS * ITEMS_PER_PRODUCER;
        Thread consumer = new Thread(() -> {
            while (consumed.get() < expectedTotal) {
                if (queue.poll() != null) {
                    consumed.incrementAndGet();
                } else {
                    Thread.onSpinWait();
                }
            }
        });

        return timeTrial(startLatch, producers, consumer, expectedTotal, consumed);
    }

    //* Shared trial runner

    private long timeTrial(CountDownLatch startLatch, Thread[] producers, Thread consumer,
                           long expectedTotal, AtomicLong consumed) {
        try {
            for (Thread p : producers) {
                p.start();
            }
            consumer.start();

            long startNanos = System.nanoTime();
            startLatch.countDown();

            for (Thread p : producers) {
                p.join();
            }
            consumer.join(30_000);
            long elapsedNanos = System.nanoTime() - startNanos;

            if (consumer.isAlive()) {
                consumer.interrupt();
                throw new IllegalStateException("Consumer did not finish within timeout; consumed=" + consumed.get()
                        + " expected=" + expectedTotal);
            }

            return Math.round(consumed.get() / (elapsedNanos / 1_000_000_000.0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private record Result(String label, double medianOpsPerSec, double minOpsPerSec, double maxOpsPerSec) {
    }
}
