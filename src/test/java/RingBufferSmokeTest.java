import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("unused")
class RingBufferSmokeTest {

    @Test
    void mpscRingBufferHandlesConcurrentProducers() throws Exception {
        int producers = 8;
        int itemsPerProducer = 150_000;
        int capacity = 1 << 6;

        MpscRingBuffer ring = new MpscRingBuffer(capacity, Long.BYTES);

        AtomicLongArray seenCounts = new AtomicLongArray(producers * itemsPerProducer);
        AtomicLong published = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        AtomicLong corrupted = new AtomicLong();

        CountDownLatch startLatch = new CountDownLatch(1);

        Thread[] producerThreads = new Thread[producers];

        long startNanos = System.nanoTime();
        try (ring) {
            for (int p = 0; p < producers; p++) {
                final int producerId = p;
                producerThreads[p] = new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < itemsPerProducer; i++) {
                            long encoded = ((long) producerId << 32) | (i & 0xFFFFFFFFL);
                            int spins = 0;
                            while (!ring.tryPublish(encoded)) {
                                spins++;
                                if (spins < 100) {
                                    Thread.onSpinWait();
                                } else {
                                    Thread.yield();
                                }
                            }
                            published.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            Thread consumerThread = new Thread(() -> {
                long expectedTotal = (long) producers * itemsPerProducer;
                int spins = 0;
                while (consumed.get() < expectedTotal) {
                    long encoded = ring.tryConsume(-1L);
                    if (encoded != -1L) {
                        spins = 0;
                        int producerId = (int) (encoded >>> 32);
                        int seq = (int) (encoded & 0xFFFFFFFFL);
                        int index = producerId * itemsPerProducer + seq;
                        if (index < 0 || index >= seenCounts.length()) {
                            corrupted.incrementAndGet();
                        } else if (seenCounts.getAndIncrement(index) != 0) {
                            corrupted.incrementAndGet();
                        }
                        consumed.incrementAndGet();
                    } else {
                        spins++;
                        if (spins < 100) {
                            Thread.onSpinWait();
                        } else {
                            Thread.yield();
                        }
                    }
                }
            });

            for (Thread t : producerThreads) {
                t.start();
            }
            consumerThread.start();
            startLatch.countDown();

            for (Thread t : producerThreads) {
                t.join();
            }
            consumerThread.join(5_000);
            if (consumerThread.isAlive()) {
                consumerThread.interrupt();
                fail("Consumer thread did not finish within timeout; consumed=" + consumed.get()
                        + " published=" + published.get());
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            long missing = 0;
            for (int i = 0; i < seenCounts.length(); i++) {
                if (seenCounts.get(i) == 0) {
                    missing++;
                }
            }

            assertEquals(0, corrupted.get(), "Detected corrupted or duplicated entries");
            assertEquals(0, missing, "Some published entries were never consumed");
            assertEquals((long) producers * itemsPerProducer, published.get(), "Unexpected published count");

            double elapsedMillis = elapsedNanos / 1_000_000.0;
            double throughputPerSec = consumed.get() / (elapsedNanos / 1_000_000_000.0);
            System.out.printf(
                    "MpscRingBuffer: %d items in %.2f ms (%.0f ops/sec, %d producers)%n",
                    consumed.get(), elapsedMillis, throughputPerSec, producers);
        }
    }
}
