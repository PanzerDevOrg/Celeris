package com.panzer.mods.celeris.pipeline.transit;

import com.panzer.mods.celeris.api.simd.VectorOperations;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.framework.ticking.IdleTickFilter;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraph;
import com.panzer.mods.celeris.pipeline.PipelineSolver;

import java.util.Arrays;

/**
 * Moves items along the segments of a {@code SegmentedGraph<TransitPayload>}
 * and across its junctions.
 *
 * <p>Per tick, for every non-empty queue: (1) progress is advanced with one
 * {@code progress += speed} pass over the queue's flat arrays, through
 * {@link VectorOperations} for queues long enough to fill vector lanes (SIMD
 * when available, scalar otherwise; bit-identical either way, since it is one
 * IEEE add per item); (2) the queue is settled: nothing passes the exit or gets
 * closer than {@link TransitConfig#minSpacing()} to the item ahead; (3) heads
 * that reached the exit are routed. Idle networks cost almost nothing
 * ({@link IdleTickFilter}).
 *
 * <p>Segments are visited, and arrivals routed, in a canonical order derived
 * from their positions (kept in a sorted copy rebuilt only when the graph's
 * segments change), never in the graph's storage order. Only the order of
 * arrivals at the same node can change an outcome, and it is the same whatever
 * history built the graph (a reload, a different build order). The simulation
 * is deterministic: the same graph, queues and router state give the same
 * result tick after tick.
 *
 * <p>Nothing here allocates per tick once the scratch arrays have grown. A
 * single thread (the graph's) must call every method.
 */
public final class TransitSolver implements PipelineSolver {

    /** {@code progress[i] += speed[i]} for {@code i < length}. Injectable so tests can compare against a plain loop. */
    @FunctionalInterface
    public interface ProgressAdvance {
        void add(float[] progress, float[] speed, int length);
    }

    private final TransitConfig config;
    private final JunctionRouter router;
    private final TransitEndpoints endpoints;
    private final TransitMigrator migrator;
    private final ProgressAdvance advance;
    private final IdleTickFilter idle = new IdleTickFilter();

    private SegmentedGraph<TransitPayload> graph;
    private TransitEvents events;
    private long tickCount;
    private int liveItems;

    private GraphSegment<TransitPayload>[] ordered = newSegmentArray(0);
    private int orderedCount;
    private long orderedVersion = Long.MIN_VALUE;

    // Queues whose head reached the exit this tick, in the order they are routed.
    @SuppressWarnings("unchecked")
    private GraphSegment<TransitPayload>[] arrivalSegment = new GraphSegment[32];
    private TransitQueue[] arrivalQueue = new TransitQueue[32];
    private boolean[] arrivalForward = new boolean[32];
    private long[] arrivalExit = new long[32];
    private long[] arrivalPrev = new long[32];
    private int arrivalCount;

    /** Below this many items a plain loop beats a vector call. */
    private static final int VECTOR_MIN_ITEMS = 64;

    private int branchMask;
    private final SegmentedGraph.SegmentVisitor<TransitPayload> maskCollector =
            (segment, direction) -> branchMask |= 1 << direction;

    public TransitSolver(TransitConfig config, JunctionRouter router, TransitEndpoints endpoints) {
        this(config, router, endpoints, null, VectorOperations::addInPlace);
    }

    /**
     * @param migrator optional; pass the same instance given to the graph. Its
     *                 orphaned items are reported as lost at the start of the next tick.
     */
    public TransitSolver(TransitConfig config, JunctionRouter router, TransitEndpoints endpoints,
                         TransitMigrator migrator) {
        this(config, router, endpoints, migrator, VectorOperations::addInPlace);
    }

    public TransitSolver(TransitConfig config, JunctionRouter router, TransitEndpoints endpoints,
                         TransitMigrator migrator, ProgressAdvance advance) {
        this.config = config;
        this.router = router;
        this.endpoints = endpoints;
        this.migrator = migrator;
        this.advance = advance;
    }

    public void bind(SegmentedGraph<TransitPayload> graph) {
        this.graph = graph;
    }

    public void setEvents(TransitEvents events) {
        this.events = events;
    }

    public long tickCount() {
        return tickCount;
    }

    /** Items currently travelling (inserted and neither delivered nor lost). */
    public int liveItems() {
        return liveItems;
    }

    /**
     * Puts an item on the segment leaving {@code node} in {@code direction}, at its
     * entry end. Returns false (item not taken) if there is no such segment or its
     * entry is full.
     */
    public boolean insert(NodeId node, int direction, int handle, float speed) {
        if (graph == null) {
            return false;
        }
        GraphSegment<TransitPayload> segment = graph.branchAt(node, direction);
        if (segment == null) {
            return false;
        }
        if (!enter(segment, isForwardFrom(segment, node.raw(), direction), handle, speed, 0f)) {
            return false;
        }
        liveItems++;
        idle.forceWake();
        return true;
    }

    public boolean insert(NodeId node, int direction, int handle) {
        return insert(node, direction, handle, config.defaultSpeed());
    }

    @Override
    public void tick() {
        if (graph == null) {
            return;
        }
        if (migrator != null) {
            liveItems -= migrator.flushOrphans();
        }
        tickCount++;
        if (!idle.shouldTick(liveItems > 0)) {
            return;
        }

        arrivalCount = 0;
        refreshOrder();
        for (int i = 0, segments = orderedCount; i < segments; i++) {
            GraphSegment<TransitPayload> segment = ordered[i];
            TransitPayload payload = segment.payload();
            if (payload.forward().size > 0) {
                step(payload.forward(), segment, true);
            }
            if (payload.backward().size > 0) {
                step(payload.backward(), segment, false);
            }
        }
        routeArrivals();
    }

    @Override
    public boolean isIdle() {
        return graph == null || liveItems <= 0;
    }

    @Override
    public String name() {
        return "item transit";
    }

    @SuppressWarnings("unchecked")
    private static <T> GraphSegment<T>[] newSegmentArray(int n) {
        return (GraphSegment<T>[]) new GraphSegment[n];
    }

    /** Re-sorts the segment copy when the graph's segments changed since the last tick. */
    private void refreshOrder() {
        long version = graph.structureVersion();
        if (version == orderedVersion) {
            return;
        }
        orderedVersion = version;
        orderedCount = graph.segmentCount();
        ordered = newSegmentArray(orderedCount);
        for (int i = 0; i < orderedCount; i++) {
            ordered[i] = graph.segmentByIndex(i);
        }
        // A segment is identified by where it starts and the block after that: unique per segment.
        java.util.Arrays.sort(ordered, (a, b) -> {
            int c = Long.compare(a.nodeA().raw(), b.nodeA().raw());
            if (c != 0) return c;
            c = Long.compare(a.rawPositionAt(1), b.rawPositionAt(1));
            if (c != 0) return c;
            c = Long.compare(a.nodeB().raw(), b.nodeB().raw());
            return c != 0 ? c : Long.compare(a.rawPositionAt(a.positionCount() - 2), b.rawPositionAt(b.positionCount() - 2));
        });
    }

    /** Advances one queue by a tick and settles it; queues whose head reached the exit are noted for routing. */
    private void step(TransitQueue queue, GraphSegment<TransitPayload> segment, boolean forward) {
        int size = queue.size;
        float[] progress = queue.progress;
        if (size >= VECTOR_MIN_ITEMS) {
            advance.add(progress, queue.speed, size);
        } else {
            float[] speed = queue.speed;
            for (int i = 0; i < size; i++) {
                progress[i] = progress[i] + speed[i];
            }
        }

        float spacing = config.minSpacing();
        float length = segment.length();
        queue.carry = progress[0] > length ? progress[0] - length : 0f;
        float limit = length;
        for (int i = 0; i < size; i++) {
            float p = progress[i];
            if (p > limit) {
                p = limit;
            }
            progress[i] = p;
            limit = p - spacing;
            if (limit < 0f) {
                limit = 0f;
            }
        }
        if (progress[0] >= length) {
            addArrival(queue, segment, forward);
        }
    }

    private void addArrival(TransitQueue queue, GraphSegment<TransitPayload> segment, boolean forward) {
        if (arrivalCount == arrivalQueue.length) {
            int grown = arrivalCount << 1;
            arrivalSegment = Arrays.copyOf(arrivalSegment, grown);
            arrivalQueue = Arrays.copyOf(arrivalQueue, grown);
            arrivalForward = Arrays.copyOf(arrivalForward, grown);
            arrivalExit = Arrays.copyOf(arrivalExit, grown);
            arrivalPrev = Arrays.copyOf(arrivalPrev, grown);
        }
        int count = segment.positionCount();
        arrivalQueue[arrivalCount] = queue;
        arrivalSegment[arrivalCount] = segment;
        arrivalForward[arrivalCount] = forward;
        arrivalExit[arrivalCount] = forward ? segment.nodeB().raw() : segment.nodeA().raw();
        arrivalPrev[arrivalCount] = segment.rawPositionAt(forward ? count - 2 : 1);
        arrivalCount++;
    }

    private void routeArrivals() {
        for (int a = 0; a < arrivalCount; a++) {
            TransitQueue queue = arrivalQueue[a];
            GraphSegment<TransitPayload> segment = arrivalSegment[a];
            boolean forward = arrivalForward[a];
            float length = segment.length();
            while (queue.size > 0 && queue.progress[0] >= length) {
                if (!routeHead(queue, segment, forward, arrivalExit[a], arrivalPrev[a])) {
                    if (!queue.blocked) {
                        queue.blocked = true;
                        if (events != null) {
                            events.queueBlocked(segment.id(), forward, true, tickCount);
                        }
                    }
                    break;
                }
            }
            arrivalQueue[a] = null;
            arrivalSegment[a] = null;
        }
    }

    private boolean routeHead(TransitQueue queue, GraphSegment<TransitPayload> segment, boolean forward,
                              long exitRaw, long prevRaw) {
        int fromDir = directionTo(exitRaw, prevRaw);
        branchMask = 0;
        graph.forEachBranchAt(new NodeId(exitRaw), maskCollector);
        int branches = branchMask;
        int candidates = (branches | endpoints.terminalMask(exitRaw)) & ~(1 << fromDir);
        if (candidates == 0) {
            return false;
        }

        int handle = queue.handle[0];
        float speed = queue.speed[0];
        float carry = queue.carry;
        for (int mask = candidates; mask != 0; ) {
            int dir = router.route(exitRaw, fromDir, handle, mask);
            if (dir < 0 || dir > 5 || (mask & (1 << dir)) == 0) {
                return false;
            }
            boolean moved;
            int reason;
            if ((branches & (1 << dir)) != 0) {
                GraphSegment<TransitPayload> next = graph.branchAt(new NodeId(exitRaw), dir);
                moved = enter(next, isForwardFrom(next, exitRaw, dir), handle, speed, carry);
                reason = TransitEvents.LEFT_TRANSFERRED;
            } else {
                moved = endpoints.deliver(exitRaw, dir, handle);
                if (moved) {
                    liveItems--;
                }
                reason = TransitEvents.LEFT_DELIVERED;
            }
            if (moved) {
                router.committed(exitRaw, dir, handle);
                if (events != null) {
                    if (queue.blocked) {
                        events.queueBlocked(segment.id(), forward, false, tickCount);
                    }
                    events.itemLeft(segment.id(), forward, handle, reason, tickCount);
                }
                queue.removeHead();
                return true;
            }
            mask &= ~(1 << dir);
        }
        return false;
    }

    /** Appends an item at the entry end of {@code segment}, honouring capacity and spacing. */
    private boolean enter(GraphSegment<TransitPayload> segment, boolean forward, int handle, float speed, float carry) {
        TransitPayload payload = segment.payload();
        TransitQueue queue = forward ? payload.forward() : payload.backward();
        float spacing = config.minSpacing();
        float length = segment.length();
        if (queue.size >= (int) (length / spacing) + 1) {
            return false;
        }
        float limit = queue.size == 0 ? length : queue.progress[queue.size - 1] - spacing;
        if (limit < 0f) {
            return false;
        }
        float progress = Math.min(carry, limit);
        queue.addTail(handle, speed, progress);
        if (events != null) {
            events.itemEntered(segment.id(), forward, handle, speed, progress, tickCount);
        }
        return true;
    }

    /** Whether leaving {@code nodeRaw} in {@code direction} along {@code segment} is its A-to-B way. */
    private static boolean isForwardFrom(GraphSegment<TransitPayload> segment, long nodeRaw, int direction) {
        return segment.nodeA().raw() == nodeRaw && segment.rawPositionAt(1) == NodeId.stepRaw(nodeRaw, direction);
    }

    /** Direction, seen from {@code fromRaw}, of the adjacent position {@code toRaw}. */
    private static int directionTo(long fromRaw, long toRaw) {
        for (int d = 0; d < 6; d++) {
            if (NodeId.stepRaw(fromRaw, d) == toRaw) {
                return d;
            }
        }
        throw new IllegalStateException("Positions are not adjacent");
    }
}
