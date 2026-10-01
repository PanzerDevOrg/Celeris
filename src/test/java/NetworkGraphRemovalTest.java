import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
class NetworkGraphRemovalTest {

    @Test
    void removingAnEndpointDeletesTheSegment() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(1, graph.segments().size());

        graph.removeNode(a);

        assertTrue(graph.segments().isEmpty());
    }

    @Test
    void removingAJunctionArmShrinksItsRemainingSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId junction = GraphTestSupport.pos(0, 0, 0);
        NodeId north = GraphTestSupport.relative(junction, GraphTestSupport.NORTH, 3);
        NodeId east = GraphTestSupport.relative(junction, GraphTestSupport.EAST, 3);

        graph.addNode(junction, GraphTestSupport.dirs(GraphTestSupport.NORTH, GraphTestSupport.EAST));
        graph.addNode(GraphTestSupport.relative(junction, GraphTestSupport.NORTH, 1), GraphTestSupport.dirs(GraphTestSupport.NORTH, GraphTestSupport.SOUTH));
        graph.addNode(GraphTestSupport.relative(junction, GraphTestSupport.NORTH, 2), GraphTestSupport.dirs(GraphTestSupport.NORTH, GraphTestSupport.SOUTH));
        graph.addNode(north, GraphTestSupport.dirs(GraphTestSupport.SOUTH));

        graph.addNode(GraphTestSupport.relative(junction, GraphTestSupport.EAST, 1), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(GraphTestSupport.relative(junction, GraphTestSupport.EAST, 2), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(east, GraphTestSupport.dirs(GraphTestSupport.WEST));

        assertEquals(2, graph.segments().size());

        graph.removeNode(north);

        assertEquals(1, graph.segments().size(), "Removing a dead-end arm should leave only the other segment");
        GraphSegment<String> remaining = GraphTestSupport.onlySegment(graph);
        assertTrue(remaining.nodeA().equals(east) || remaining.nodeB().equals(east));
    }

    @Test
    void removingAJunctionThatSeparatesTwoSegmentsLeavesTheNetworkDisconnected() {
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

        assertEquals(2, graph.segments().size(), "Sanity check before removal");

        graph.removeNode(mid);

        assertTrue(graph.segments().isEmpty(),
                "Destroying the pipe block at mid physically severs start from end -- no segment should span the gap");
    }

    @Test
    void removingUnknownNodeIsANoOp() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId ghost = GraphTestSupport.pos(99, 99, 99);

        assertDoesNotThrow(() -> graph.removeNode(ghost));
        assertTrue(graph.segments().isEmpty());
    }

    @Test
    void segmentAtReturnsNullAfterItsSegmentIsRemoved() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST, 3);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        NodeId interior = GraphTestSupport.relative(a, GraphTestSupport.EAST, 1);
        graph.addNode(interior, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(GraphTestSupport.relative(a, GraphTestSupport.EAST, 2), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertNotNull(graph.segmentAt(interior));

        graph.removeNode(a);

        assertNull(graph.segmentAt(interior));
    }
}
