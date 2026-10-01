import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("unused")
class NetworkGraphPayloadTest {

    @Test
    void newSegmentsReceiveAFreshPayloadFromTheSupplier() {
        AtomicInteger counter = new AtomicInteger();
        SegmentedGraphImpl<Integer> graph = GraphTestSupport.newGraph(counter::incrementAndGet);

        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        GraphSegment<Integer> segment = graph.segments().iterator().next();
        assertEquals(1, segment.payload());
    }

    @Test
    void rebuildingAffectedSegmentsAllocatesNewPayloadsForEach() {
        AtomicInteger counter = new AtomicInteger();
        SegmentedGraphImpl<Integer> graph = GraphTestSupport.newGraph(counter::incrementAndGet);

        NodeId start = GraphTestSupport.pos(0, 0, 0);
        NodeId mid = GraphTestSupport.relative(start, GraphTestSupport.EAST, 5);
        NodeId end = GraphTestSupport.relative(start, GraphTestSupport.EAST, 10);

        graph.addNode(start, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        for (int i = 1; i < 10; i++) {
            if (i == 5) continue;
            graph.addNode(GraphTestSupport.relative(start, GraphTestSupport.EAST, i),
                    GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        }
        graph.addNode(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(end, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        int payloadsSoFar = counter.get();

        graph.addNode(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST, GraphTestSupport.SOUTH));

        assertEquals(payloadsSoFar + 2, counter.get(),
                "Splitting one segment into two should allocate exactly two new payloads");
    }
}
