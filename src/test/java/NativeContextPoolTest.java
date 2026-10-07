import com.panzer.mods.celeris.core.memory.NativeContextPoolTestAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The lock-free context pool, with fake "contexts" (counters) instead of native ones. */
@SuppressWarnings("unused")
class NativeContextPoolTest {

    @Test
    void reusesWhatWasReleased() {
        AtomicLong created = new AtomicLong();
        AtomicLong destroyed = new AtomicLong();
        Object pool = NativeContextPoolTestAccess.create(4, created::incrementAndGet, ctx -> destroyed.incrementAndGet());

        long first = NativeContextPoolTestAccess.acquire(pool);
        NativeContextPoolTestAccess.release(pool, first);
        for (int i = 0; i < 1000; i++) {
            long ctx = NativeContextPoolTestAccess.acquire(pool);
            assertEquals(first, ctx, "a single thread should keep getting its own context back");
            NativeContextPoolTestAccess.release(pool, ctx);
        }
        assertEquals(1, created.get());
        assertEquals(0, destroyed.get());
    }

    @Test
    void boundedIdleCountAndClear() {
        AtomicLong created = new AtomicLong();
        Set<Long> destroyed = ConcurrentHashMap.newKeySet();
        Object pool = NativeContextPoolTestAccess.create(3, created::incrementAndGet, destroyed::add);
        int capacity = NativeContextPoolTestAccess.capacity(pool);
        assertEquals(4, capacity, "capacity rounds up to a power of two");

        List<Long> held = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            held.add(NativeContextPoolTestAccess.acquire(pool));
        }
        assertEquals(10, created.get(), "empty pool creates on demand");
        for (long ctx : held) {
            NativeContextPoolTestAccess.release(pool, ctx);
        }
        assertEquals(10 - capacity, destroyed.size(), "only `capacity` contexts stay idle; the rest are freed");

        NativeContextPoolTestAccess.clear(pool);
        assertEquals(10, destroyed.size(), "clear frees every idle context");
        assertEquals(10, Set.copyOf(held).size());
    }

    @Test
    void neverHandsTheSameContextToTwoHolders() throws Exception {
        AtomicLong created = new AtomicLong();
        AtomicLong destroyed = new AtomicLong();
        Object pool = NativeContextPoolTestAccess.create(4, created::incrementAndGet, ctx -> destroyed.incrementAndGet());
        Set<Long> inUse = ConcurrentHashMap.newKeySet();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100_000; i++) {
                        long ctx = NativeContextPoolTestAccess.acquire(pool);
                        assertTrue(inUse.add(ctx), "context " + ctx + " handed out twice");
                        Thread.onSpinWait();
                        assertTrue(inUse.remove(ctx));
                        NativeContextPoolTestAccess.release(pool, ctx);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            executor.shutdownNow();
        }
        // A release into a full pool frees the context and a later acquire from an
        // empty one creates another, so the total created can grow; what is bounded
        // is how many exist at once: one per thread plus the idle slots.
        long live = created.get() - destroyed.get();
        assertTrue(live <= threads + NativeContextPoolTestAccess.capacity(pool),
                live + " contexts alive for " + threads + " threads");
    }
}
