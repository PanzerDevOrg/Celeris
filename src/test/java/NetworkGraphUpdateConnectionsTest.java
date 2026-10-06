import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphUpdateConnectionsTest {

    @Test
    void updateConnectionsOnUnknownPositionRegistersItAsANewNode() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.updateConnections(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));

        assertEquals(1, graph.segments().size());
    }

    @Test
    void turningAJunctionIntoAStraightThroughMergesItsTwoSegments() {
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

        assertEquals(2, graph.segments().size());

        graph.updateConnections(mid, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));

        assertEquals(1, graph.segments().size());
        assertEquals(10, GraphTestSupport.onlySegment(graph).length());
    }

    @Test
    void removingAllConnectionsFromAJunctionDeletesItsSegments() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId a = GraphTestSupport.pos(0, 0, 0);
        NodeId b = GraphTestSupport.relative(a, GraphTestSupport.EAST);

        graph.addNode(a, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(b, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.NORTH));
        assertEquals(1, graph.segments().size());

        graph.updateConnections(a, GraphTestSupport.dirs());

        assertTrue(graph.segments().isEmpty());
    }
}
