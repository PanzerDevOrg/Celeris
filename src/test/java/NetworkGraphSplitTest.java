import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphSplitTest {

    @Test
    void insertingJunctionInMiddleOfStraightRunSplitsIntoTwoSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
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

        assertEquals(1, graph.segments().size(), "Sanity check before split");

        graph.addNode(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST, GraphTestSupport.SOUTH));

        Collection<GraphSegment<String>> segments = graph.segments(); // API contract is Collection, not Set
        assertEquals(2, segments.size());

        for (GraphSegment<String> segment : segments) {
            assertEquals(5, segment.length());
            assertTrue(segment.nodeA().equals(mid) || segment.nodeB().equals(mid));
        }
    }

    @Test
    void insertingJunctionAtSegmentEndpointDoesNotSplitAnything() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST, 3);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(GraphTestSupport.relative(a, GraphTestSupport.EAST, 1), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(GraphTestSupport.relative(a, GraphTestSupport.EAST, 2), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(1, graph.segments().size());

        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH, GraphTestSupport.SOUTH));

        assertEquals(1, graph.segments().size(), "Re-registering an existing junction should not split it");
    }

    @Test
    void removingMiddleJunctionMergesTwoSegmentsBackIntoOne() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId start = GraphTestSupport.pos(0, 0, 0);
        NodeId mid = GraphTestSupport.relative(start, GraphTestSupport.EAST, 5);
        NodeId end = GraphTestSupport.relative(start, GraphTestSupport.EAST, 10);

        graph.addNode(start, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        for (int i = 1; i < 10; i++) {
            if (i == 5) continue;
            graph.addNode(GraphTestSupport.relative(start, GraphTestSupport.EAST, i),
                    GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        }
        graph.addNode(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST, GraphTestSupport.SOUTH));
        graph.addNode(end, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(2, graph.segments().size(), "Sanity check before merge");

        graph.updateConnections(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));

        assertEquals(1, graph.segments().size());
        GraphSegment<String> merged = GraphTestSupport.onlySegment(graph);
        assertEquals(10, merged.length());
    }
}
