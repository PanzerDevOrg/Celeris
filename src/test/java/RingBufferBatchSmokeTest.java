import com.panzer.mods.celeris.core.concurrency.MpscRingBuffer;
import com.panzer.mods.celeris.framework.async.AsyncResultQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness coverage for {@link MpscRingBuffer#tryConsumeBatch}, mirroring
 * {@code RingBufferSmokeTest}'s coverage of {@code tryConsume}. This exists
 * because the batch-drain path exercises different bookkeeping (single
 * readCursor release per batch instead of per item, early-stop on the first
 * unpublished slot within the batch) and had no correctness test of its own
 * -- only a throughput benchmark, which does not assert on ordering,
 * duplication, or loss.
 */
@SuppressWarnings("unused")
class RingBufferBatchSmokeTest {

    @Test
    void tryConsumeBatchHandlesConcurrentProducersWithoutLossOrDuplication() throws Exception {
        int producers = 8;
        int itemsPerProducer = 150_000;
        int capacity = 1 << 6; // small on purpose: forces frequent wraparound
        int maxBatch = 16; // smaller than capacity: forces partial batches too

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
                long[] out = new long[maxBatch];
                long expectedTotal = (long) producers * itemsPerProducer;
                int spins = 0;
                while (consumed.get() < expectedTotal) {
                    int drained = ring.tryConsumeBatch(out, 0, maxBatch);
                    if (drained > 0) {
                        spins = 0;
                        for (int i = 0; i < drained; i++) {
                            long encoded = out[i];
                            int producerId = (int) (encoded >>> 32);
                            int seq = (int) (encoded & 0xFFFFFFFFL);
                            int index = producerId * itemsPerProducer + seq;
                            if (index < 0 || index >= seenCounts.length()) {
                                corrupted.incrementAndGet();
                            } else if (seenCounts.getAndIncrement(index) != 0) {
                                corrupted.incrementAndGet();
                            }
                        }
                        consumed.addAndGet(drained);
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

            assertEquals(0, corrupted.get(), "Detected corrupted or duplicated entries via tryConsumeBatch");
            assertEquals(0, missing, "Some published entries were never consumed via tryConsumeBatch");
            assertEquals((long) producers * itemsPerProducer, published.get(), "Unexpected published count");

            double elapsedMillis = elapsedNanos / 1_000_000.0;
            double throughputPerSec = consumed.get() / (elapsedNanos / 1_000_000_000.0);
            System.out.printf(
                    "MpscRingBuffer (batch): %d items in %.2f ms (%.0f ops/sec, %d producers, batch=%d)%n",
                    consumed.get(), elapsedMillis, throughputPerSec, producers, maxBatch);
        }
    }

    @Test
    void tryConsumeBatchReturnsZeroWhenEmpty() {
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 4, Long.BYTES)) {
            long[] out = new long[8];
            int drained = ring.tryConsumeBatch(out, 0, 8);
            assertEquals(0, drained, "Draining an empty ring must return 0, not block or throw");
        }
    }

    @Test
    void tryConsumeBatchStopsAtFirstGapAndReturnsPartialBatch() {
        try (MpscRingBuffer ring = new MpscRingBuffer(1 << 4, Long.BYTES)) {
            // Publish exactly 3 items; nothing after them is available yet.
            for (long v = 1; v <= 3; v++) {
                assertTrue(ring.tryPublish(v), "publish should succeed on empty ring");
            }

            long[] out = new long[10];
            int drained = ring.tryConsumeBatch(out, 0, 10);

            assertEquals(3, drained, "Batch must stop at the first unpublished slot, not block waiting for more");
            for (int i = 0; i < 3; i++) {
                assertEquals(i + 1L, out[i], "Item at batch position " + i + " does not match publish order");
            }
        }
    }

    /**
     * Correctness coverage for {@link AsyncResultQueue}, specifically targeting
     * the reserveSlot/write-object/publishReserved ordering that makes the
     * side-channel {@code AtomicReferenceArray} safe without its own locking.
     * If that ordering were ever broken (e.g. someone "simplifies" offer() back
     * to tryPublishIndexed-then-write), this test should start failing with
     * either null results observed in drainTo or corrupted/duplicated payloads,
     * not silently pass.
     */
    static
    class AsyncResultQueueSmokeTest {

        @Test
        void handlesConcurrentProducersWithoutLossDuplicationOrNulls() throws Exception {
            int producers = 8;
            int itemsPerProducer = 150_000;
            int capacity = 1 << 6; // small on purpose: forces frequent slot reuse

            try (AsyncResultQueue<WorkResult> queue = new AsyncResultQueue<>(capacity)) {
                AtomicLongArray seenCounts = new AtomicLongArray(producers * itemsPerProducer);
                AtomicLong published = new AtomicLong();
                AtomicLong consumed = new AtomicLong();
                AtomicLong corrupted = new AtomicLong();
                AtomicLong nullsObserved = new AtomicLong();

                CountDownLatch startLatch = new CountDownLatch(1);
                Thread[] producerThreads = new Thread[producers];

                for (int p = 0; p < producers; p++) {
                    final int producerId = p;
                    producerThreads[p] = new Thread(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < itemsPerProducer; i++) {
                                WorkResult result = new WorkResult(producerId, i);
                                int spins = 0;
                                while (!queue.offer(result)) {
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

                long expectedTotal = (long) producers * itemsPerProducer;
                Thread consumerThread = new Thread(() -> {
                    int spins = 0;
                    while (consumed.get() < expectedTotal) {
                        int drained = queue.drainTo(result -> {
                            if (result == null) {
                                nullsObserved.incrementAndGet();
                                return;
                            }
                            int index = result.producerId() * itemsPerProducer + result.sequence();
                            if (index < 0 || index >= seenCounts.length()) {
                                corrupted.incrementAndGet();
                            } else if (seenCounts.getAndIncrement(index) != 0) {
                                corrupted.incrementAndGet();
                            }
                        }, 16);

                        if (drained > 0) {
                            spins = 0;
                            consumed.addAndGet(drained);
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
                consumerThread.join(10_000);
                if (consumerThread.isAlive()) {
                    consumerThread.interrupt();
                    fail("Consumer thread did not finish within timeout; consumed=" + consumed.get()
                            + " published=" + published.get());
                }

                long missing = 0;
                for (int i = 0; i < seenCounts.length(); i++) {
                    if (seenCounts.get(i) == 0) {
                        missing++;
                    }
                }

                assertEquals(0, nullsObserved.get(),
                        "drainTo observed a null result -- reserveSlot/publishReserved ordering is broken");
                assertEquals(0, corrupted.get(), "Detected corrupted or duplicated results");
                assertEquals(0, missing, "Some offered results were never drained");
                assertEquals(expectedTotal, published.get(), "Unexpected published count");
            }
        }

        @Test
        void offerRejectsNull() {
            try (AsyncResultQueue<String> queue = new AsyncResultQueue<>(1 << 4)) {
                assertTrue(assertThrowsNpe(queue), "offer(null) must throw NullPointerException");
            }
        }

        private boolean assertThrowsNpe(AsyncResultQueue<String> queue) {
            try {
                queue.offer(null);
                return false;
            } catch (NullPointerException expected) {
                return true;
            }
        }

        @Test
        void drainToReturnsZeroWhenEmpty() {
            try (AsyncResultQueue<String> queue = new AsyncResultQueue<>(1 << 4)) {
                int[] callCount = {0};
                int drained = queue.drainTo(r -> callCount[0]++, 8);
                assertEquals(0, drained, "Draining an empty queue must return 0, not block");
                assertEquals(0, callCount[0], "Consumer must not be called when nothing was drained");
            }
        }

        private record WorkResult(int producerId, int sequence) {
        }
    }
}
