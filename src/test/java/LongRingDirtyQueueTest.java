import com.panzer.mods.celeris.core.dirty.LongRingDirtyQueue;
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
}
