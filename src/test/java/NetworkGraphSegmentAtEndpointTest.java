import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * segmentAt(pos) must resolve nodes (endpoints and junctions), not just
 * interior positions -- consumers like PipeNetwork.pickInsertionSegment
 * call segmentAt on exactly these positions (a pipe adjacent to an
 * external inventory is a node, essentially never an interior position).
 */
@SuppressWarnings("unused")
class NetworkGraphSegmentAtEndpointTest {

    @Test
    void segmentAtResolvesSimpleEndpoint() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST));

        assertNotNull(graph.segmentAt(a));
        assertNotNull(graph.segmentAt(b));
        assertEquals(graph.segmentAt(a), graph.segmentAt(b));
    }

    @Test
    @SuppressWarnings("UnnecessaryLocalVariable")
    void segmentAtResolvesEndpointOfLongerStraightRun() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId start = GraphTestSupport.pos(0, 0, 0);

        GraphTestSupport.addStraightLine(graph, start, GraphTestSupport.EAST, 5);

        NodeId firstEndpoint = start;
        NodeId lastEndpoint = GraphTestSupport.relative(start, GraphTestSupport.EAST, 4);
        NodeId interior = GraphTestSupport.relative(start, GraphTestSupport.EAST, 2);

        assertNotNull(graph.segmentAt(firstEndpoint));
        assertNotNull(graph.segmentAt(lastEndpoint));
        assertNotNull(graph.segmentAt(interior));
        assertEquals(graph.segmentAt(firstEndpoint), graph.segmentAt(interior));
        assertEquals(graph.segmentAt(lastEndpoint), graph.segmentAt(interior));
    }

    @Test
    void segmentAtResolvesAJunctionToOneOfItsTouchingSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId junction = GraphTestSupport.pos(0, 0, 0);
        NodeId east = GraphTestSupport.relative(junction, GraphTestSupport.EAST);
        NodeId north = GraphTestSupport.relative(junction, GraphTestSupport.NORTH);
        NodeId south = GraphTestSupport.relative(junction, GraphTestSupport.SOUTH);

        graph.addNode(junction, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH, GraphTestSupport.SOUTH));
        graph.addNode(east, GraphTestSupport.dirs(GraphTestSupport.WEST));
        graph.addNode(north, GraphTestSupport.dirs(GraphTestSupport.SOUTH));
        graph.addNode(south, GraphTestSupport.dirs(GraphTestSupport.NORTH));

        assertEquals(3, graph.segments().size());

        GraphSegment<String> atJunction = graph.segmentAt(junction);
        assertNotNull(atJunction);
        assertTrue(graph.segments().contains(atJunction));
        assertTrue(atJunction.nodeA().equals(junction) || atJunction.nodeB().equals(junction));
    }

    @Test
    void segmentAtReturnsNullForUnregisteredPosition() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        assertNull(graph.segmentAt(GraphTestSupport.pos(42, 42, 42)));
    }

    @Test
    void segmentAtReturnsNullForIsolatedNodeWithNoSegment() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        graph.addNode(a, GraphTestSupport.dirs());

        assertNull(graph.segmentAt(a));
    }
}
