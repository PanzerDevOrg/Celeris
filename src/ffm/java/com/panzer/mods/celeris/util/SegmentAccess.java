package com.panzer.mods.celeris.util;

import java.lang.foreign.MemorySegment;

/**
 * Mixin-injected interface (see the {@code celeris$} prefix convention) that
 * exposes a Netty buffer's backing {@link MemorySegment} directly, so the
 * FFM backend can read/write it without a heap round-trip. Internal wiring
 * for {@code FriendlyByteBufMixin} -- not something to implement yourself.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public interface SegmentAccess {

    MemorySegment celeris$asMemorySegment();
}
