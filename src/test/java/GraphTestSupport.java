import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;

@SuppressWarnings("unused")
final class GraphTestSupport {

    // Direction ordinal order, matching BranchlessTables: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST.
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    private static final int[] OPPOSITE = {UP, DOWN, SOUTH, NORTH, EAST, WEST};

    private GraphTestSupport() {
    }

    static SegmentedGraphImpl<String> newGraph() {
        return new SegmentedGraphImpl<>(new NetworkTopologyImpl(), () -> "");
    }

    static <T> SegmentedGraphImpl<T> newGraph(java.util.function.Supplier<T> payload) {
        return new SegmentedGraphImpl<>(new NetworkTopologyImpl(), payload);
    }

    static NodeId pos(int x, int y, int z) {
        return NodeId.pack(x, y, z);
    }

    static NodeId relative(NodeId from, int direction, int count) {
        NodeId result = from;
        for (int i = 0; i < count; i++) {
            result = result.step(direction);
        }
        return result;
    }

    static NodeId relative(NodeId from, int direction) {
        return relative(from, direction, 1);
    }

    static int opposite(int direction) {
        return OPPOSITE[direction];
    }

    static ConnectionMask dirs(int... directions) {
        int bits = 0;
        for (int d : directions) {
            bits |= 1 << d;
        }
        return ConnectionMask.of(bits);
    }

    @SuppressWarnings("SameParameterValue")
    static void addStraightLine(SegmentedGraphImpl<String> graph, NodeId start, int axisDirection, int blockCount) {
        int opposite = opposite(axisDirection);
        for (int i = 0; i < blockCount; i++) {
            NodeId pos = relative(start, axisDirection, i);
            boolean isFirst = i == 0;
            boolean isLast = i == blockCount - 1;

            if (isFirst && isLast) {
                graph.addNode(pos, dirs());
            } else if (isFirst) {
                graph.addNode(pos, dirs(axisDirection));
            } else if (isLast) {
                graph.addNode(pos, dirs(opposite));
            } else {
                graph.addNode(pos, dirs(axisDirection, opposite));
            }
        }
    }

    static GraphSegment<String> onlySegment(SegmentedGraphImpl<String> graph) {
        var segments = graph.segments();
        if (segments.size() != 1) {
            throw new AssertionError("Expected exactly 1 segment, found " + segments.size());
        }
        return segments.iterator().next();
    }
}
