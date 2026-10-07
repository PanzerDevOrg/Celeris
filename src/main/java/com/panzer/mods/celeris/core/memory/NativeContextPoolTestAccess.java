package com.panzer.mods.celeris.core.memory;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Test-only accessor exposing the package-private {@link NativeContextPool}
 * to tests living outside this package.
 */
public final class NativeContextPoolTestAccess {
    private NativeContextPoolTestAccess() {}

    public static Object create(int capacity, LongSupplier factory, LongConsumer destructor) {
        return new NativeContextPool("test context", capacity, factory, destructor);
    }

    public static long acquire(Object pool) {
        return ((NativeContextPool) pool).acquire();
    }

    public static void release(Object pool, long ctx) {
        ((NativeContextPool) pool).release(ctx);
    }

    public static void clear(Object pool) {
        ((NativeContextPool) pool).clear();
    }

    public static int capacity(Object pool) {
        return ((NativeContextPool) pool).capacity();
    }
}
