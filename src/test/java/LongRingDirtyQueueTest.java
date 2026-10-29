import net.bichal.mods.celeris.core.dirty.LongRingDirtyQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class LongRingDirtyQueueTest {

    @Test
    void markingTheSameIndexTwiceBeforeItIsPolledOnlyEnqueuesItOnce() {
        LongRingDirtyQueue queue = new LongRingDirtyQueue();

        queue.markDirty(7);
        queue.markDirty(7);
        queue.markDirty(7);

        assertEquals(1, queue.pendingCount());
        assertEquals(7, queue.poll());
        assertEquals(-1, queue.poll());
        assertTrue(queue.isIdle());
    }

    @Test
    void afterBeingPolledAnIndexCanBeMarkedDirtyAgain() {
        LongRingDirtyQueue queue = new LongRingDirtyQueue();

        queue.markDirty(3);
        assertEquals(3, queue.poll());
        assertTrue(queue.isIdle());

        queue.markDirty(3);
        assertEquals(1, queue.pendingCount());
        assertEquals(3, queue.poll());
    }

    @Test
    void capacityGrowsPastTheInitialSixtyFourBitWords() {
        LongRingDirtyQueue queue = new LongRingDirtyQueue();

        int farIndex = 10_000;
        queue.markDirty(farIndex);
        queue.markDirty(farIndex); // still deduped after growth

        assertEquals(1, queue.pendingCount());
        assertEquals(farIndex, queue.poll());
    }

    @Test
    void growthRacingPollNeverLosesOrDuplicatesAMark() throws Exception {
        final int total = 400_000;
        try (LongRingDirtyQueue queue = new LongRingDirtyQueue(1 << 18)) {
            java.util.concurrent.atomic.AtomicInteger produced = new java.util.concurrent.atomic.AtomicInteger();
            int[] seen = new int[total];
            Thread producer = new Thread(() -> {
                for (int i = 0; i < total; i++) {
                    queue.markDirty(i);
                    produced.incrementAndGet();
                }
            });
            producer.start();
            int polled = 0;
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (polled < total && System.nanoTime() < deadline) {
                int idx = queue.poll();
                if (idx >= 0) {
                    seen[idx]++;
                    polled++;
                }
            }
            producer.join();
            assertEquals(total, polled);
            for (int i = 0; i < total; i++) {
                assertEquals(1, seen[i], "index " + i);
            }
            queue.markDirty(total - 1); // not stuck "pending" after its poll
            assertEquals(total - 1, queue.poll());
        }
    }

    @Test
    void aFullRingSpillsToTheOverflowListInsteadOfDropping() {
        try (LongRingDirtyQueue queue = new LongRingDirtyQueue(16)) {
            int n = 100;
            for (int i = 0; i < n; i++) {
                queue.markDirty(i);
            }
            assertEquals(n, queue.pendingCount());
            java.util.BitSet seen = new java.util.BitSet();
            int idx;
            while ((idx = queue.poll()) >= 0) {
                assertTrue(!seen.get(idx), "duplicate " + idx);
                seen.set(idx);
            }
            assertEquals(n, seen.cardinality());
            assertTrue(queue.isIdle());
        }
    }
}
