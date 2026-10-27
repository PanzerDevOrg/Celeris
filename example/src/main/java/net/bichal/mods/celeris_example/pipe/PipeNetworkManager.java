package net.bichal.mods.celeris_example.pipe;

import net.bichal.mods.celeris.api.transit.TransitDelta;
import net.bichal.mods.celeris.core.topology.*;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentGraphListener;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraph;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import net.bichal.mods.celeris.network.PayloadCompression;
import net.bichal.mods.celeris.network.TransitDeltaPackets;
import net.bichal.mods.celeris.pipeline.transit.*;
import net.bichal.mods.celeris_example.pipe.net.PipePayload;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Item transport for one dimension: a {@link SegmentedGraph} of pipes whose
 * segments carry {@link TransitPayload} queues, a {@link TransitSolver} that
 * moves the items along them once per tick, and the glue that makes the items
 * visible to players without an entity per item.
 *
 * <ul>
 *   <li><b>Server:</b> items are integer handles ({@link PipeItemTable}); the
 *       solver moves them, {@link PipeEndpoints} hands them to inventories,
 *       {@code TransitMigrator} keeps them when pipes are placed or broken.</li>
 *   <li><b>Network:</b> {@link TransitDeltaRecorder} turns the solver's events
 *       into compact {@link TransitDelta} messages, sent only when something
 *       changes; {@link PipeSyncMessage} carries the segment shapes and which
 *       handle is which item.</li>
 *   <li><b>Client:</b> {@code PipeClientState} + {@code PipeRenderer} compute each
 *       item's position every frame from the last news, with no per-tick data.</li>
 * </ul>
 *
 * <p>Not persisted: items travelling when the dimension unloads are lost. A real
 * mod saves them with {@code SegmentStateIO} and {@code TransitStateCodec}.
 */
public final class PipeNetworkManager implements AutoCloseable {

    private static final Map<ResourceKey<Level>, PipeNetworkManager> INSTANCES = new HashMap<>();

    /** Segments per message when a lot of the network has to be described at once. */
    private static final int POSITIONS_PER_MESSAGE = 100_000;

    public static PipeNetworkManager forLevel(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), ignoredKey -> new PipeNetworkManager(level));
    }

    public static void tickIfPresent(ServerLevel level) {
        PipeNetworkManager manager = INSTANCES.get(level.dimension());
        if (manager != null) {
            manager.tick();
        }
    }

    public static void unload(ServerLevel level) {
        PipeNetworkManager removed = INSTANCES.remove(level.dimension());
        if (removed != null) {
            removed.close();
        }
    }

    /** A player logged in, respawned or arrived in a dimension: tell them what is in it (or that nothing is). */
    public static void sendState(ServerPlayer player) {
        PipeNetworkManager manager = INSTANCES.get(player.level().dimension());
        if (manager != null) {
            manager.snapshot(player);
        } else {
            send(player, new PipeSyncMessage(true, 0L, new int[0], new int[0], new int[0],
                    new long[0], new long[0][], new long[0]).toBytes(), false);
        }
    }

    private final ServerLevel level;
    private final NetworkTopology topology = new NetworkTopologyImpl();
    private final PipeItemTable items = new PipeItemTable();
    private final SegmentedGraph<TransitPayload> graph;
    private final TransitSolver solver;
    private final TransitDeltaRecorder recorder;
    private final List<byte[]> pendingDeltas = new ArrayList<>();

    // Shape of every segment the clients can draw, kept from the graph's listener.
    private final Long2ObjectOpenHashMap<long[]> shapes = new Long2ObjectOpenHashMap<>();
    private final LongArrayList createdIds = new LongArrayList();
    private final LongArrayList removedIds = new LongArrayList();

    // Pipe blocks currently shown as FULL, and whether the structure or a queue's blocked state changed since.
    private final LongOpenHashSet fullNodes = new LongOpenHashSet();
    private boolean fullStateStale;

    private PipeNetworkManager(ServerLevel level) {
        this.level = level;
        TransitMigrator migrator = new TransitMigrator(this::dropLostItem);
        this.graph = new SegmentedGraphImpl<>(topology, TransitPayload::new, migrator);
        this.solver = new TransitSolver(TransitConfig.defaults(), new RoundRobinRouter(),
                new PipeEndpoints(level, items), migrator);
        this.recorder = new TransitDeltaRecorder(0L, pendingDeltas::add);
        TransitEvents events = new TransitEvents() {
            @Override
            public void itemEntered(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
                recorder.itemEntered(segmentId, forward, handle, speed, progress, tick);
            }

            @Override
            public void itemLeft(long segmentId, boolean forward, int handle, int reason, long tick) {
                recorder.itemLeft(segmentId, forward, handle, reason, tick);
            }

            @Override
            public void itemSpeedChanged(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
                recorder.itemSpeedChanged(segmentId, forward, handle, speed, progress, tick);
            }

            @Override
            public void queueBlocked(long segmentId, boolean forward, boolean blocked, long tick) {
                fullStateStale = true;
            }
        };
        solver.setEvents(events);
        migrator.setEvents(events, solver::tickCount);
        graph.setListener(new SegmentGraphListener<>() {
            @Override
            public void segmentCreated(GraphSegment<TransitPayload> segment) {
                int count = segment.positionCount();
                if (count <= PipeSyncMessage.MAX_POSITIONS) {
                    long[] shape = new long[count];
                    for (int i = 0; i < count; i++) {
                        shape[i] = segment.rawPositionAt(i);
                    }
                    shapes.put(segment.id(), shape);
                }
                createdIds.add(segment.id());
                fullStateStale = true;
            }

            @Override
            public void segmentRemoved(GraphSegment<TransitPayload> segment) {
                shapes.remove(segment.id());
                removedIds.add(segment.id());
                fullStateStale = true;
            }
        });
        solver.bind(graph);
    }

    public void addPipe(BlockPos pos, ConnectionMask connections) {
        graph.addNode(NodeIdCodec.of(pos.asLong()), connections);
    }

    public void removePipe(BlockPos pos) {
        graph.removeNode(NodeIdCodec.of(pos.asLong()));
    }

    /** Skips the graph call (and the fusion re-trace it triggers) when the mask hasn't actually changed -- see {@code WireNetworkManager#updateWireConnections} for why this guard matters. */
    public void updatePipeConnections(BlockPos pos, ConnectionMask connections) {
        NodeId id = NodeIdCodec.of(pos.asLong());
        if (topology.connectionsOf(id).equals(connections)) {
            return;
        }
        graph.updateConnections(id, connections);
    }

    /**
     * Puts one item of {@code stack} into the pipe at {@code pos}, heading away from the
     * clicked side when it can. Only pipe ends and junctions are entry points (an item
     * enters a segment at its end). Returns false, taking nothing, when it cannot.
     */
    public boolean insert(BlockPos pos, Direction clickedFace, ItemStack stack) {
        NodeId node = NodeIdCodec.of(pos.asLong());
        if (topology.denseIndexOf(node) < 0) {
            return false;
        }
        int bits = topology.connectionsOf(node).bits();
        int handle = items.add(stack.copyWithCount(1));
        int preferred = clickedFace.getOpposite().ordinal();
        if ((bits & (1 << preferred)) != 0 && solver.insert(node, preferred, handle)) {
            return true;
        }
        for (int rest = bits; rest != 0; rest &= rest - 1) {
            if (solver.insert(node, Integer.numberOfTrailingZeros(rest), handle)) {
                return true;
            }
        }
        items.release(handle);
        return false;
    }

    private void tick() {
        solver.tick();
        if (fullStateStale) {
            fullStateStale = false;
            refreshFullState();
        }
        broadcast();
    }

    private void dropLostItem(int handle, float speed, long nodeRaw) {
        ItemStack stack = items.release(handle);
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(new NodeId(nodeRaw)));
        level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    /** A pipe end shows FULL while the queue that reaches it is held up by a receiver that refuses. */
    private void refreshFullState() {
        LongOpenHashSet wanted = new LongOpenHashSet();
        for (GraphSegment<TransitPayload> segment : graph.segments()) {
            TransitPayload payload = segment.payload();
            if (payload.forward().isBlocked() || payload.backward().isBlocked()) {
                wanted.add(segment.rawPositionAt(0));
                wanted.add(segment.rawPositionAt(segment.positionCount() - 1));
            }
        }
        LongArrayList toClear = new LongArrayList();
        for (long raw : fullNodes) {
            if (!wanted.contains(raw)) {
                toClear.add(raw);
            }
        }
        LongArrayList toSet = new LongArrayList();
        for (long raw : wanted) {
            if (!fullNodes.contains(raw)) {
                toSet.add(raw);
            }
        }
        // Block updates only after the loop: they must never run inside a walk over the graph.
        for (long raw : toClear) {
            fullNodes.remove(raw);
            applyFullState(raw, false);
        }
        for (long raw : toSet) {
            fullNodes.add(raw);
            applyFullState(raw, true);
        }
    }

    private void applyFullState(long raw, boolean full) {
        BlockPos pos = BlockPos.of(NodeIdCodec.toPackedPosition(new NodeId(raw)));
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof PipeBlock && state.getValue(PipeBlock.FULL) != full) {
            level.setBlock(pos, state.setValue(PipeBlock.FULL, full), Block.UPDATE_CLIENTS);
        }
    }

    // --- network -------------------------------------------------------------------------

    /** Sends what changed this tick: item definitions and segment shapes first, then the movements. */
    private void broadcast() {
        long tick = solver.tickCount();
        recorder.drain(tick);
        boolean news = items.hasChanges() || !createdIds.isEmpty() || !removedIds.isEmpty()
                || !pendingDeltas.isEmpty() || solver.liveItems() > 0;
        List<ServerPlayer> players = level.players();
        if (news && !players.isEmpty()) {
            LongArrayList created = new LongArrayList();
            LongOpenHashSet createdSet = new LongOpenHashSet(createdIds);
            for (long id : createdIds) {
                if (shapes.containsKey(id)) {
                    created.add(id);
                }
            }
            LongArrayList removed = new LongArrayList();
            for (long id : removedIds) {
                if (!createdSet.contains(id)) {
                    removed.add(id);
                }
            }
            List<byte[]> syncs = syncMessages(false, tick, items.definedHandles(), items.definedItems(),
                    items.releasedHandles(), created, removed);
            for (ServerPlayer player : players) {
                for (byte[] message : syncs) {
                    send(player, message, false);
                }
                for (byte[] delta : pendingDeltas) {
                    send(player, delta, true);
                }
            }
        }
        createdIds.clear();
        removedIds.clear();
        pendingDeltas.clear();
        items.syncFlushed();
    }

    /** Describes the whole dimension to one player: every segment, every item, every moving thing. */
    private void snapshot(ServerPlayer player) {
        long tick = solver.tickCount();
        int[] handles = items.liveHandles();
        int[] itemIds = new int[handles.length];
        for (int i = 0; i < handles.length; i++) {
            itemIds[i] = items.itemIdOf(handles[i]);
        }
        LongArrayList all = new LongArrayList(shapes.keySet());
        for (byte[] message : syncMessages(true, tick, handles, itemIds, new int[0], all, new LongArrayList())) {
            send(player, message, false);
        }

        TransitDelta.Writer writer = new TransitDelta.Writer();
        writer.reset(tick);
        for (GraphSegment<TransitPayload> segment : graph.segments()) {
            if (!shapes.containsKey(segment.id())) {
                continue;
            }
            TransitPayload payload = segment.payload();
            for (int dir = 0; dir < 2; dir++) {
                TransitQueue queue = dir == 0 ? payload.forward() : payload.backward();
                for (int i = 0; i < queue.size(); i++) {
                    if (writer.isFull()) {
                        send(player, writer.toBytes(), true);
                        writer.reset(tick);
                    }
                    writer.enter(queue.handleAt(i), segment.id(), dir == 0, queue.progressAt(i), queue.speedAt(i), tick);
                }
            }
        }
        if (writer.opCount() > 0) {
            send(player, writer.toBytes(), true);
        }
    }

    /** Splits the segment shapes over as many messages as the size limit needs; everything else goes in the first. */
    private List<byte[]> syncMessages(boolean reset, long tick, int[] defHandles, int[] defItems, int[] released,
                                      LongArrayList created, LongArrayList removed) {
        List<byte[]> out = new ArrayList<>();
        int from = 0;
        boolean first = true;
        do {
            LongArrayList ids = new LongArrayList();
            List<long[]> shapesInMessage = new ArrayList<>();
            int positions = 0;
            while (from < created.size() && positions < POSITIONS_PER_MESSAGE) {
                long[] shape = shapes.get(created.getLong(from));
                ids.add(created.getLong(from++));
                shapesInMessage.add(shape);
                positions += shape.length;
            }
            PipeSyncMessage message = new PipeSyncMessage(first && reset, tick,
                    first ? defHandles : new int[0], first ? defItems : new int[0], first ? released : new int[0],
                    ids.toLongArray(), shapesInMessage.toArray(new long[0][]),
                    first ? removed.toLongArray() : new long[0]);
            out.add(message.toBytes());
            first = false;
        } while (from < created.size());
        return out;
    }

    private static void send(ServerPlayer player, byte[] message, boolean delta) {
        PacketDistributor.sendToPlayer(player, new PipePayload(delta,
                delta ? TransitDeltaPackets.encode(message) : PayloadCompression.compress(message)));
    }

    @Override
    public void close() {
        // SegmentedGraphImpl holds no off-heap allocation of its own (unlike
        // DiscreteNodeStore) -- segment/payload state is plain Java objects,
        // so there's nothing to free here. Kept as a no-op AutoCloseable for
        // symmetry with WireNetworkManager.
    }
}
