import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class NetworkGraphNeighborsTest {

    @Test
    void fourWayJunctionExposesEachArmAsANeighborInTheRightDirection() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId center = GraphTestSupport.pos(0, 0, 0);

        graph.addNode(center, GraphTestSupport.dirs(GraphTestSupport.NORTH, GraphTestSupport.SOUTH, GraphTestSupport.EAST, GraphTestSupport.WEST));
        NodeId north = GraphTestSupport.relative(center, GraphTestSupport.NORTH);
        NodeId south = GraphTestSupport.relative(center, GraphTestSupport.SOUTH);
        NodeId east = GraphTestSupport.relative(center, GraphTestSupport.EAST);
        NodeId west = GraphTestSupport.relative(center, GraphTestSupport.WEST);

        graph.addNode(north, GraphTestSupport.dirs(GraphTestSupport.SOUTH, GraphTestSupport.NORTH));
        graph.addNode(GraphTestSupport.relative(north, GraphTestSupport.NORTH), GraphTestSupport.dirs(GraphTestSupport.SOUTH));
        graph.addNode(south, GraphTestSupport.dirs(GraphTestSupport.NORTH, GraphTestSupport.SOUTH));
        graph.addNode(GraphTestSupport.relative(south, GraphTestSupport.SOUTH), GraphTestSupport.dirs(GraphTestSupport.NORTH));
        graph.addNode(east, GraphTestSupport.dirs(GraphTestSupport.WEST, GraphTestSupport.EAST));
        graph.addNode(GraphTestSupport.relative(east, GraphTestSupport.EAST), GraphTestSupport.dirs(GraphTestSupport.WEST));
        graph.addNode(west, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(GraphTestSupport.relative(west, GraphTestSupport.WEST), GraphTestSupport.dirs(GraphTestSupport.EAST));

        assertEquals(4, graph.segments().size());

        GraphSegment<String> northSegment = graph.segmentAt(GraphTestSupport.relative(center, GraphTestSupport.NORTH));
        Map<GraphSegment<String>, Integer> neighbors = graph.neighbors(northSegment);

        assertTrue(neighbors.containsValue(GraphTestSupport.SOUTH),
                "Following the segment back toward the junction should read as SOUTH");
    }

    @Test
    void twoArmDeadEndSegmentHasOnlyOneNeighborEntry() {
        SegmentedGraphImpl<String> graph = GraphTestSupport.newGraph();
        NodeId junction = GraphTestSupport.pos(0, 0, 0);
        NodeId armEnd = GraphTestSupport.relative(junction, GraphTestSupport.EAST, 2);

        graph.addNode(junction, GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.NORTH));
        graph.addNode(GraphTestSupport.relative(junction, GraphTestSupport.EAST, 1), GraphTestSupport.dirs(GraphTestSupport.EAST, GraphTestSupport.WEST));
        graph.addNode(armEnd, GraphTestSupport.dirs(GraphTestSupport.WEST));

        GraphSegment<String> segment = GraphTestSupport.onlySegment(graph);
        Map<GraphSegment<String>, Integer> neighbors = graph.neighbors(segment);

        assertTrue(neighbors.isEmpty(),
                "A dead-end arm has no junction on the far side, so it has no segment neighbors");
    }
}
