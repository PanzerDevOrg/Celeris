package net.bichal.mods.celeris_example.pipe.client;

import net.bichal.mods.celeris.api.transit.ClientItemTable;
import net.bichal.mods.celeris.network.PayloadCompression;
import net.bichal.mods.celeris.network.TransitDeltaPackets;
import net.bichal.mods.celeris_example.pipe.PipeSyncMessage;
import net.bichal.mods.celeris_example.pipe.net.PipePayload;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Everything the client knows about the items in the pipes: no entity per
 * item, just {@link ClientItemTable} (what Celeris's {@code TransitDelta}
 * messages describe), the shape of each segment and which item each handle is.
 * Fed from the network thread's main-thread handler and read by the renderer,
 * so it needs no locking.
 */
public final class PipeClientState {

    private static final double NANOS_PER_TICK = 50_000_000d;
    /** Items keep moving this long without news from the server, then hold still. */
    private static final double MAX_EXTRAPOLATION_TICKS = 3d;

    public static final ClientItemTable ITEMS = new ClientItemTable();
    public static final Long2ObjectOpenHashMap<long[]> SEGMENTS = new Long2ObjectOpenHashMap<>();
    public static final Int2IntOpenHashMap ITEM_OF_HANDLE = new Int2IntOpenHashMap();

    private static long syncTick;
    private static long syncNanos;
    private static boolean synced;

    private PipeClientState() {
    }

    public static void receive(PipePayload payload) {
        if (payload.delta()) {
            // Validated as a whole by Celeris: a bad message changes nothing.
            TransitDeltaPackets.decode(payload.body(), ITEMS);
            return;
        }
        byte[] raw;
        try {
            raw = PayloadCompression.decompress(payload.body());
        } catch (RuntimeException e) {
            return;
        }
        PipeSyncMessage message = PipeSyncMessage.read(raw);
        if (message != null) {
            apply(message);
        }
    }

    private static void apply(PipeSyncMessage m) {
        if (m.reset()) {
            clear();
        }
        for (int i = 0; i < m.defHandles().length; i++) {
            ITEM_OF_HANDLE.put(m.defHandles()[i], m.defItems()[i]);
        }
        for (int handle : m.released()) {
            ITEM_OF_HANDLE.remove(handle);
        }
        for (int i = 0; i < m.segmentIds().length; i++) {
            SEGMENTS.put(m.segmentIds()[i], m.segmentPositions()[i]);
        }
        for (long id : m.removedSegments()) {
            SEGMENTS.remove(id);
        }
        syncTick = m.serverTick();
        syncNanos = System.nanoTime();
        synced = true;
    }

    public static void clear() {
        // A new table: ClientItemTable has no reset, and a reset is rare.
        ITEMS.clear();
        SEGMENTS.clear();
        ITEM_OF_HANDLE.clear();
        synced = false;
    }

    public static boolean isSynced() {
        return synced;
    }

    /** The server tick being shown, fractional: the last tick announced plus the time since it arrived. */
    public static double now() {
        double elapsed = (System.nanoTime() - syncNanos) / NANOS_PER_TICK;
        return syncTick + Math.min(elapsed, MAX_EXTRAPOLATION_TICKS);
    }
}
