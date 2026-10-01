import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphBasicSegmentTest {

    @Test
    void twoEndpointsWithNoBlocksBetweenFormNoSegment() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);

        graph.addNode(a, GraphTestSupport.dirs());

        assertTrue(graph.segments().isEmpty());
    }

    @Test
    void twoAdjacentJunctionsFormOneSegmentOfLengthOne() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH, GraphTestSupport.SOUTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH, GraphTestSupport.SOUTH));

        GraphSegment<String> segment = GraphTestSupport.onlySegment(graph);
        assertEquals(1, segment.length());
        assertEquals(2, segment.positions().size());
    }

    @Test
    void straightRunOfTenBlocksCollapsesIntoOneSegment() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId start = GraphTestSupport.pos(0, 0, 0);

        graph.addNode(start, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        for (int i = 1; i < 9; i++) {
            graph.addNode(GraphTestSupport.relative(start, GraphTestSupport.EAST, i),
                    GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        }
        NodeId end = GraphTestSupport.relative(start, GraphTestSupport.EAST, 9);
        graph.addNode(end, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(1, graph.segments().size());
        GraphSegment<String> segment = GraphTestSupport.onlySegment(graph);
        assertEquals(9, segment.length());
        assertEquals(10, segment.positions().size());
    }

    @Test
    void deadEndPipeFormsNoSegmentUntilConnectedOnBothSides() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));

        assertTrue(graph.segments().isEmpty());
    }

    @Test
    void cornerAndDeadEndFormOneSegmentOfLengthOne() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST));

        GraphSegment<String> segment = GraphTestSupport.onlySegment(graph);
        assertEquals(1, segment.length());
        assertEquals(2, segment.positions().size());
    }
}
