package com.panzer.mods.celeris.framework.capability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One shared capability handler instance per owner type, created lazily on
 * first request. Useful when a capability handler is stateless (or shares
 * state across all instances of a block) and allocating a fresh one per
 * block entity would just be wasted objects.
 *
 * @param <H> the capability handler type
 */
public final class SingletonCapabilityRegistry<H> {

    private final ConcurrentHashMap<Class<?>, H> singletons;

    public SingletonCapabilityRegistry() {
        this.singletons = new ConcurrentHashMap<>();
    }

    /** Returns the shared handler for {@code ownerType}, creating it via {@code factory} on first call. */
    public H handlerFor(Class<?> ownerType, Supplier<H> factory) {
        return singletons.computeIfAbsent(ownerType, ignoredKey -> factory.get());
    }

    public boolean isRegistered(Class<?> ownerType) {
        return singletons.containsKey(ownerType);
    }
}
