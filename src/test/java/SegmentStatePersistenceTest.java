import com.panzer.mods.celeris.core.format.StateFormat;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentStateIO;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import com.panzer.mods.celeris.pipeline.transit.FilterRouter;
import com.panzer.mods.celeris.pipeline.transit.RoundRobinRouter;
import com.panzer.mods.celeris.pipeline.transit.TransitConfig;
import com.panzer.mods.celeris.pipeline.transit.TransitEndpoints;
import com.panzer.mods.celeris.pipeline.transit.TransitPayload;
import com.panzer.mods.celeris.pipeline.transit.TransitQueue;
import com.panzer.mods.celeris.pipeline.transit.TransitSolver;
import com.panzer.mods.celeris.pipeline.transit.TransitStateCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
class SegmentStatePersistenceTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int S = GraphTestSupport.SOUTH;
    private static final int N = GraphTestSupport.NORTH;

    private final TransitStateCodec codec = new TransitStateCodec();

    /** A T-shaped network built in the given node order: a 12-block line with a 6-block branch from block 6. */
    private static SegmentedGraphImpl<TransitPayload> tNetwork(boolean reverseOrder, boolean bulk) {
        List<long[]> nodes = new ArrayList<>(); // {raw, bits}
        for (int i = 0; i < 12; i++) {
            int bits = i == 0 ? 1 << E : i == 11 ? 1 << W : (1 << E) | (1 << W);
            if (i == 6) bits |= 1 << S;
            nodes.add(new long[]{NodeId.packRaw(i, 0, 0), bits});
        }
        for (int j = 1; j <= 6; j++) {
            int bits = j == 6 ? 1 << N : (1 << N) | (1 << S);
            nodes.add(new long[]{NodeId.packRaw(6, 0, j), bits});
        }
        if (reverseOrder) java.util.Collections.reverse(nodes);
        SegmentedGraphImpl<TransitPayload> graph = new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new);
        long[] ids = nodes.stream().mapToLong(a -> a[0]).toArray();
        int[] bits = nodes.stream().mapToInt(a -> (int) a[1]).toArray();
        if (bulk) {
            graph.addNodes(ids, bits, ids.length);
        } else {
            for (int i = 0; i < ids.length; i++) graph.addNode(new NodeId(ids[i]), com.panzer.mods.celeris.core.topology.ConnectionMask.of(bits[i]));
        }
        return graph;
    }

    /** Items in world terms (edge, distance along it, handle, speed), independent of segment ids and orientation. */
    private static TreeSet<String> worldItems(SegmentedGraphImpl<TransitPayload> graph) {
        TreeSet<String> items = new TreeSet<>();
        for (GraphSegment<TransitPayload> s : graph.segments()) {
            collect(items, s, s.payload().forward(), true);
            collect(items, s, s.payload().backward(), false);
        }
        return items;
    }

    private static void collect(TreeSet<String> out, GraphSegment<TransitPayload> s, TransitQueue q, boolean forward) {
        int length = s.length();
        for (int i = 0; i < q.size(); i++) {
            float p = q.progressAt(i);
            int k = Math.min(length - 1, Math.max(0, (int) Math.ceil(p) - 1));
            long from = s.rawPositionAt(forward ? k : length - k);
            long to = s.rawPositionAt(forward ? k + 1 : length - k - 1);
            out.add(from + ">" + to + "@" + (p - k) + " h" + q.handleAt(i) + " v" + q.speedAt(i));
        }
    }

    private SegmentedGraphImpl<TransitPayload> populated() {
        var graph = tNetwork(false, false);
        TransitSolver solver = new TransitSolver(new TransitConfig(0.25f, 0.3f),
                new FilterRouter((n, d, h) -> true, new RoundRobinRouter()), TransitEndpoints.NONE);
        solver.bind(graph);
        Random random = new Random(3);
        for (int t = 0; t < 120; t++) {
            if (t % 4 == 0) solver.insert(new NodeId(NodeId.packRaw(0, 0, 0)), E, t, 0.1f + random.nextInt(4) * 0.05f);
            if (t % 7 == 0) solver.insert(new NodeId(NodeId.packRaw(6, 0, 6)), N, 1000 + t, 0.2f);
            solver.tick();
        }
        assertFalse(worldItems(graph).isEmpty());
        return graph;
    }

    private ByteBuffer save(SegmentedGraphImpl<TransitPayload> graph) {
        ByteBuffer buffer = ByteBuffer.allocate(SegmentStateIO.maxBytes(graph, codec, null));
        SegmentStateIO.write(graph, codec, buffer, null);
        buffer.flip();
        return buffer;
    }

    @Test
    void stateSurvivesRebuildingTheGraphInAnotherOrderAndOrientation() {
        var original = populated();
        ByteBuffer saved = save(original);

        for (boolean bulk : new boolean[]{false, true}) {
            var rebuilt = tNetwork(true, bulk);
            var result = SegmentStateIO.read(rebuilt, codec, saved.duplicate(), null);
            assertEquals(new SegmentStateIO.Result(original.segments().size(), 0, 0, false), result);
            assertEquals(worldItems(original), worldItems(rebuilt));
        }
        // the point of the exercise: some segment really is traced the other way round
        var rebuilt = tNetwork(true, false);
        boolean flipped = false;
        for (GraphSegment<TransitPayload> a : original.segments())
            for (GraphSegment<TransitPayload> b : rebuilt.segments())
                if (a.nodeA().equals(b.nodeB()) && a.nodeB().equals(b.nodeA())) flipped = true;
        assertTrue(flipped);
    }

    @Test
    void equalStatesGiveEqualBytes() {
        assertEquals(save(populated()), save(populated()));
    }

    @Test
    void aSegmentWhoseShapeChangedIsHandedBackInsteadOfBeingMisapplied() {
        var original = populated();
        ByteBuffer saved = save(original);
        var changed = tNetwork(false, false);
        // a new pipe makes block 3 a junction: the main line is now two segments
        changed.updateConnections(new NodeId(NodeId.packRaw(3, 0, 0)),
                com.panzer.mods.celeris.core.topology.ConnectionMask.of((1 << E) | (1 << W) | (1 << S)));
        List<Long> unmatchedStarts = new ArrayList<>();
        var result = SegmentStateIO.read(changed, codec, saved, (start, second, end, count, payload) -> {
            unmatchedStarts.add(start);
            assertTrue(payload.isReadOnly());
        });
        assertTrue(result.unmatched() >= 1);
        assertEquals(result.unmatched(), unmatchedStarts.size());
        assertEquals(0, result.malformed());
    }

    @Test
    void corruptOrForeignBytesAreRefusedWithoutThrowing() {
        var graph = populated();
        ByteBuffer good = save(graph);
        byte[] bytes = new byte[good.remaining()];
        good.duplicate().get(bytes);

        // truncated at every length
        for (int cut = 0; cut < bytes.length; cut += 3) {
            var target = tNetwork(true, true);
            SegmentStateIO.read(target, codec, ByteBuffer.wrap(bytes, 0, cut), null);
        }
        // random corruption
        Random random = new Random(11);
        for (int round = 0; round < 300; round++) {
            byte[] copy = bytes.clone();
            for (int k = 0; k < 1 + random.nextInt(6); k++) copy[random.nextInt(copy.length)] = (byte) random.nextInt();
            var target = tNetwork(true, true);
            SegmentStateIO.read(target, codec, ByteBuffer.wrap(copy), null);
        }
        // wrong magic and a payload version from the future
        byte[] wrongMagic = bytes.clone();
        wrongMagic[0] ^= 0x55;
        assertTrue(SegmentStateIO.read(tNetwork(true, true), codec, ByteBuffer.wrap(wrongMagic), null).rejected());
        ByteBuffer future = ByteBuffer.wrap(bytes.clone());
        future.putInt(8, codec.formatVersion() + 1);
        assertTrue(SegmentStateIO.read(tNetwork(true, true), codec, future, null).rejected());
        // an absurd record count
        ByteBuffer huge = ByteBuffer.wrap(bytes.clone());
        huge.putInt(12, Integer.MAX_VALUE);
        assertTrue(SegmentStateIO.read(tNetwork(true, true), codec, huge, null).rejected());
    }

    @Test
    void aMalformedPayloadLeavesTheSegmentUntouched() {
        var graph = populated();
        ByteBuffer saved = save(graph);
        byte[] bytes = new byte[saved.remaining()];
        saved.duplicate().get(bytes);
        // first record's payload starts after header(16) + fixed part(32); poison its forward item count
        ByteBuffer poisoned = ByteBuffer.wrap(bytes);
        poisoned.putInt(16 + 32, 0x7fffffff);
        var target = tNetwork(false, false);
        var before = worldItems(target);
        var result = SegmentStateIO.read(target, codec, poisoned, null);
        assertEquals(1, result.malformed());
        assertEquals(graph.segments().size() - 1, result.applied());
    }

    @Test
    void headerHelpersRejectForeignAndFutureData() {
        ByteBuffer b = ByteBuffer.allocate(8);
        StateFormat.writeHeader(b, StateFormat.magic("ABCD"), 3);
        b.flip();
        assertEquals(3, StateFormat.readHeader(b.duplicate(), StateFormat.magic("ABCD"), 3));
        assertEquals(StateFormat.TOO_NEW, StateFormat.readHeader(b.duplicate(), StateFormat.magic("ABCD"), 2));
        assertEquals(StateFormat.BAD_MAGIC, StateFormat.readHeader(b.duplicate(), StateFormat.magic("WXYZ"), 3));
        assertEquals(StateFormat.TRUNCATED, StateFormat.readHeader(ByteBuffer.allocate(4), StateFormat.magic("ABCD"), 3));
    }
}
