package bench;

import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;
import com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend;
import org.jctools.queues.MpscArrayQueue;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * P worker threads hand results (8-byte values: tickets, handles) to one
 * consumer, the game thread pattern: each queue moves N values, best and
 * median of several rounds in millions of values per second. Bounded queues
 * of 65,536 slots; producers spin when full, the consumer when empty.
 */
public final class QueueBench {

    interface Q {
        boolean offer(long v);

        long poll(); // Long.MIN_VALUE when empty
    }

    static Q celeris() {
        MpscRingBuffer ring = new MpscRingBuffer(new UnsafeMemoryBackend(), 1 << 16, 8);
        return new Q() {
            public boolean offer(long v) {
                return ring.tryPublish(v);
            }

            public long poll() {
                return ring.tryConsume(Long.MIN_VALUE);
            }
        };
    }

    static Q boxed(Queue<Long> q) {
        return new Q() {
            public boolean offer(long v) {
                return q.offer(v);
            }

            public long poll() {
                Long v = q.poll();
                return v == null ? Long.MIN_VALUE : v;
            }
        };
    }

    static double run(Q q, int producers, long total) throws InterruptedException {
        long perProducer = total / producers;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final long base = (long) p << 40;
            threads[p] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (long i = 0; i < perProducer; i++) {
                    while (!q.offer(base + i)) {
                        Thread.onSpinWait();
                    }
                }
            });
            threads[p].start();
        }
        long expected = perProducer * producers;
        long received = 0;
        long checksum = 0;
        long t0 = System.nanoTime();
        start.countDown();
        while (received < expected) {
            long v = q.poll();
            if (v == Long.MIN_VALUE) {
                Thread.onSpinWait();
                continue;
            }
            checksum += v;
            received++;
        }
        long nanos = System.nanoTime() - t0;
        for (Thread t : threads) {
            t.join();
        }
        if (checksum == 42) {
            System.out.print("");
        }
        return expected / (nanos / 1e9) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        long total = 20_000_000;
        int rounds = 9;
        String[] names = {"Celeris MpscRingBuffer", "JCTools MpscArrayQueue", "ArrayBlockingQueue", "ConcurrentLinkedQueue"};
        System.out.println("queue,producers,best_mops,median_mops");
        for (int producers : new int[]{1, 2, 3}) {
            for (int k = 0; k < names.length; k++) {
                double[] r = new double[rounds];
                for (int i = 0; i < rounds; i++) {
                    Q q = switch (k) {
                        case 0 -> celeris();
                        case 1 -> boxed(new MpscArrayQueue<>(1 << 16));
                        case 2 -> boxed(new ArrayBlockingQueue<>(1 << 16));
                        default -> boxed(new ConcurrentLinkedQueue<>());
                    };
                    r[i] = run(q, producers, total);
                }
                Arrays.sort(r);
                System.out.printf("%s,%d,%.1f,%.1f%n", names[k], producers, r[rounds - 1], r[rounds / 2]);
            }
        }
    }
}
