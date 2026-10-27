import net.bichal.mods.celeris.core.topology.NetworkTopologyImpl;
import net.bichal.mods.celeris.core.topology.NodeId;
import net.bichal.mods.celeris.graph.segmented.GraphSegment;
import net.bichal.mods.celeris.graph.segmented.SegmentGraphListener;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraph;
import net.bichal.mods.celeris.graph.segmented.SegmentedGraphImpl;
import net.bichal.mods.celeris.pipeline.continuous.SIMDPipelineSolverImpl;
import net.bichal.mods.celeris.pipeline.continuous.SegmentTickBatch;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class SegmentGraphObservationTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int N = GraphTestSupport.NORTH;
    private static final int S = GraphTestSupport.SOUTH;

    private static final class Log implements SegmentGraphListener<String> {
        final List<String> events = new ArrayList<>();

        @Override
        public void segmentCreated(GraphSegment<String> s) {
            events.add("+" + s.id());
        }

        @Override
        public void segmentRemoved(GraphSegment<String> s) {
            events.add("-" + s.id());
        }

        @Override
        public void payloadDirty(GraphSegment<String> s) {
            events.add("~" + s.id());
        }
    }

    private static void line(SegmentedGraphImpl<String> graph, NodeId start, int dir, int count) {
        int back = GraphTestSupport.opposite(dir);
        for (int i = 0; i < count; i++) {
            NodeId pos = GraphTestSupport.relative(start, dir, i);
            graph.addNode(pos, i == 0 ? GraphTestSupport.dirs(dir)
                    : i == count - 1 ? GraphTestSupport.dirs(back) : GraphTestSupport.dirs(dir, back));
        }
    }

    @Test
    void listenerSeesOnlyNetChanges() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        Log log = new Log();
        graph.setListener(log);
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(graph, start, E, 11);

        long first = graph.segments().iterator().next().id();
        assertEquals(List.of("+" + first), log.events.subList(log.events.size() - 1, log.events.size()),
                "the line finally forms one segment");
        // intermediate partial lines never form a segment, so exactly one creation overall
        assertEquals(1, log.events.stream().filter(e -> e.startsWith("+")).count());
        log.events.clear();

        NodeId mid = GraphTestSupport.relative(start, E, 5);
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
        assertEquals(3, log.events.size(), log.events.toString());
        assertEquals("-" + first, log.events.get(0));
        log.events.clear();

        // same shape, only the unconnected third branch changes: nothing structural happened
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, N));
        assertTrue(log.events.isEmpty(), log.events.toString());
    }

    @Test
    void dirtyIsDeduplicatedAndSkipsRemovedSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        Log log = new Log();
        graph.setListener(log);
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        line(graph, start, E, 11);
        GraphSegment<String> segment = graph.segments().iterator().next();
        log.events.clear();

        graph.markPayloadDirty(segment);
        graph.markPayloadDirty(segment);
        assertEquals(1, graph.flushDirty());
        assertEquals(List.of("~" + segment.id()), log.events);
        assertEquals(0, graph.flushDirty());

        graph.markPayloadDirty(segment);
        graph.removeNode(start);
        log.events.clear();
        assertEquals(0, graph.flushDirty());
        assertTrue(log.events.isEmpty());
    }

    @Test
    void allocationFreeVisitorsMatchTheMapViews() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId hub = GraphTestSupport.pos(0, 0, 0);
        graph.addNode(hub, GraphTestSupport.dirs(E, W, N, S));
        int[] arms = {E, W, N, S};
        for (int dir : arms) {
            NodeId end = GraphTestSupport.relative(hub, dir, 4);
            for (int i = 3; i >= 1; i--) {
                graph.addNode(GraphTestSupport.relative(hub, dir, i),
                        GraphTestSupport.dirs(dir, GraphTestSupport.opposite(dir)));
            }
            graph.addNode(end, GraphTestSupport.dirs(GraphTestSupport.opposite(dir)));
        }
        assertEquals(4, graph.segments().size());

        Map<GraphSegment<String>, Integer> viaCallback = new HashMap<>();
        graph.forEachBranchAt(hub, viaCallback::put);
        assertEquals(graph.branchesAt(hub), viaCallback);
        for (int dir : arms) {
            assertEquals(graph.segmentAt(GraphTestSupport.relative(hub, dir, 2)), graph.branchAt(hub, dir));
        }
        assertEquals(null, graph.branchAt(hub, GraphTestSupport.UP));

        GraphSegment<String> any = graph.segmentAt(GraphTestSupport.relative(hub, E, 2));
        Map<GraphSegment<String>, Integer> neighbours = new HashMap<>();
        graph.forEachNeighbor(any, neighbours::put);
        assertEquals(graph.neighbors(any), neighbours);
        assertEquals(3, neighbours.size());

        assertEquals(graph.segments().size(), graph.segmentCount());
        List<GraphSegment<String>> byIndex = new ArrayList<>();
        for (int i = 0; i < graph.segmentCount(); i++) byIndex.add(graph.segmentByIndex(i));
        assertEquals(new ArrayList<>(graph.segments()), byIndex);
    }

    @Test
    void solverTickDoesNotAllocatePerTick() {
        SegmentedGraphImpl<float[]> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), () -> new float[]{0.01f, 0f});
        for (int row = 0; row < 300; row++) {
            NodeId start = GraphTestSupport.pos(0, 0, row * 2);
            graph.addNode(start, GraphTestSupport.dirs(E));
            graph.addNode(GraphTestSupport.relative(start, E), GraphTestSupport.dirs(E, W));
            graph.addNode(GraphTestSupport.relative(start, E, 2), GraphTestSupport.dirs(W));
        }
        assertEquals(300, graph.segments().size());

        SIMDPipelineSolverImpl<float[]> solver = new SIMDPipelineSolverImpl<>();
        solver.bind(graph, new SegmentTickBatch.Extractor<>() {
            @Override
            public void gather(GraphSegment<float[]> segment, SegmentTickBatch batch) {
                batch.add(segment.payload()[0], segment.payload()[1]);
            }

            @Override
            public void scatter(GraphSegment<float[]> segment, int index, SegmentTickBatch batch) {
                segment.payload()[1] = batch.bufferLevels()[index];
            }
        });
        for (int i = 0; i < 12_000; i++) solver.tick();

        // Allocation is read per window and the quietest window counts: a JIT compilation
        // landing inside one window must not decide the result.
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long perTick = Long.MAX_VALUE;
        for (int window = 0; window < 6; window++) {
            long before = threads.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 1000; i++) solver.tick();
            perTick = Math.min(perTick, (threads.getThreadAllocatedBytes(tid) - before) / 1000);
        }

        assertTrue(perTick < 64, "solver tick allocated " + perTick + " bytes");
        assertTrue(graph.segments().iterator().next().payload()[1] > 1f);
    }
}
