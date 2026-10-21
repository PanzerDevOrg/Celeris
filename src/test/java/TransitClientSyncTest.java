import com.panzer.mods.celeris.api.transit.ClientItemTable;
import com.panzer.mods.celeris.api.transit.TransitMotion;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.segmented.GraphSegment;
import com.panzer.mods.celeris.graph.segmented.SegmentedGraphImpl;
import com.panzer.mods.celeris.network.TransitDeltaPackets;
import com.panzer.mods.celeris.pipeline.transit.RoundRobinRouter;
import com.panzer.mods.celeris.pipeline.transit.TransitConfig;
import com.panzer.mods.celeris.pipeline.transit.TransitDeltaRecorder;
import com.panzer.mods.celeris.pipeline.transit.TransitEndpoints;
import com.panzer.mods.celeris.pipeline.transit.TransitMigrator;
import com.panzer.mods.celeris.pipeline.transit.TransitPayload;
import com.panzer.mods.celeris.pipeline.transit.TransitQueue;
import com.panzer.mods.celeris.pipeline.transit.TransitSolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole loop: simulate on the "server", ship deltas, rebuild the picture on the "client", compare every tick. */
@SuppressWarnings("unused")
class TransitClientSyncTest {

    private static final int E = GraphTestSupport.EAST;
    private static final int W = GraphTestSupport.WEST;
    private static final int S = GraphTestSupport.SOUTH;
    private static final int N = GraphTestSupport.NORTH;

    private final Set<Integer> delivered = new HashSet<>();
    private final Map<Long, Integer> terminals = new HashMap<>();
    private final TransitEndpoints endpoints = new TransitEndpoints() {
        public int terminalMask(long node) {
            Integer d = terminals.get(node);
            return d == null ? 0 : 1 << d;
        }

        public boolean deliver(long node, int direction, int handle) {
            delivered.add(handle);
            return true;
        }
    };
    private final List<Integer> lost = new ArrayList<>();
    private final TransitMigrator migrator = new TransitMigrator((h, s, n) -> lost.add(h));
    private final SegmentedGraphImpl<TransitPayload> graph =
            new SegmentedGraphImpl<>(new NetworkTopologyImpl(), TransitPayload::new, migrator);
    private final TransitSolver solver = new TransitSolver(new TransitConfig(0.25f, 0.2f), new RoundRobinRouter(), endpoints, migrator);
    private final ClientItemTable client = new ClientItemTable();
    private int messages;
    private int bytesSent;
    private long naiveBytes; // what sending every item's x, y, z every tick would cost
    private final TransitDeltaRecorder recorder = new TransitDeltaRecorder(0, msg -> {
        messages++;
        var payload = TransitDeltaPackets.encode(msg);
        bytesSent += payload.data().length;
        assertTrue(TransitDeltaPackets.decode(payload, client));
    });

    TransitClientSyncTest() {
        solver.bind(graph);
        solver.setEvents(recorder);
        migrator.setEvents(recorder, solver::tickCount);
    }

    private void line(NodeId start, int dir, int count) {
        int back = GraphTestSupport.opposite(dir);
        for (int i = 0; i < count; i++) {
            graph.addNode(GraphTestSupport.relative(start, dir, i), i == 0 ? GraphTestSupport.dirs(dir)
                    : i == count - 1 ? GraphTestSupport.dirs(back) : GraphTestSupport.dirs(dir, back));
        }
    }

    private Map<Long, GraphSegment<TransitPayload>> byId() {
        Map<Long, GraphSegment<TransitPayload>> map = new HashMap<>();
        for (GraphSegment<TransitPayload> s : graph.segments()) map.put(s.id(), s);
        return map;
    }

    private void endTick() {
        recorder.drain(solver.tickCount());
    }

    /** Every server item is on the client at the same segment, direction and (within tolerance) place. */
    private void assertSameWorld(String when) {
        float spacing = 0.25f;
        int serverItems = 0;
        float[] a = new float[3];
        float[] b = new float[3];
        long now = solver.tickCount();
        for (GraphSegment<TransitPayload> s : graph.segments()) {
            long[] path = new long[s.positionCount()];
            for (int i = 0; i < path.length; i++) path[i] = s.rawPositionAt(i);
            for (boolean forward : new boolean[]{true, false}) {
                TransitQueue q = forward ? s.payload().forward() : s.payload().backward();
                serverItems += q.size();

                // what the client knows about this queue, head first, with the spacing rule applied as the server does
                List<Integer> slots = new ArrayList<>();
                for (int slot = 0; slot < client.slotLimit(); slot++) {
                    if (client.isLive(slot) && client.segmentAt(slot) == s.id() && client.forwardAt(slot) == forward) slots.add(slot);
                }
                slots.sort((x, y) -> Long.compare(client.arrivalAt(x), client.arrivalAt(y)));
                assertEquals(q.size(), slots.size(), when + ": items on segment " + s.id());
                float ahead = Float.NaN;
                for (int i = 0; i < q.size(); i++) {
                    int slot = slots.get(i);
                    assertEquals(q.handleAt(i), client.itemAt(slot), when + ": queue order on segment " + s.id());
                    float p = client.progressAt(slot, now, s.length());
                    p = i == 0 ? p : TransitMotion.limitBehind(p, ahead, spacing);
                    ahead = p;
                    // A head that just left frees the item behind it for the next tick only, so the client may lead the
                    // server by up to one tick of motion; anything else is a real disagreement.
                    float tolerance = i <= 1 ? q.speedAt(i) + 2e-3f : 2e-3f;
                    assertEquals(q.progressAt(i), p, tolerance, when + ": progress of " + q.handleAt(i) + " seg " + s.id() + " len " + s.length()
                            + " idx " + i + "/" + q.size() + " serverSpeed " + q.speedAt(i) + " blocked " + q.isBlocked()
                            + " client(start=" + client.startProgressAt(slot) + " speed=" + client.speedAt(slot) + " tick=" + client.entryTickAt(slot) + ") now=" + now);
                    TransitMotion.position(path, path.length, forward, q.progressAt(i), a);
                    TransitMotion.position(path, path.length, forward, p, b);
                    assertEquals(a[0], b[0], tolerance);
                    assertEquals(a[2], b[2], tolerance);
                }
            }
        }
        // orphans are still shown by the client until they are lost
        assertEquals(serverItems + migrator.orphanCount(), client.size(), when + ": item count");
    }

    @Test
    void theClientReproducesTheServerThroughJunctionsMigrationAndSpeedChanges() {
        NodeId origin = GraphTestSupport.pos(0, 0, 0);
        line(origin, E, 31);
        NodeId mid = GraphTestSupport.relative(origin, E, 15);
        graph.updateConnections(mid, GraphTestSupport.dirs(E, W, S));
        line(GraphTestSupport.relative(mid, S, 10), N, 10);
        graph.updateConnections(GraphTestSupport.relative(mid, S, 1), GraphTestSupport.dirs(N, S));
        terminals.put(GraphTestSupport.relative(origin, E, 30).raw(), E);
        terminals.put(GraphTestSupport.relative(mid, S, 10).raw(), S);

        int sent = 0;
        for (int t = 0; t < 400; t++) {
            if (t % 3 == 0 && solver.insert(origin, E, sent, 0.2f)) sent++;
            if (t == 60) graph.updateConnections(GraphTestSupport.relative(origin, E, 5), GraphTestSupport.dirs(E, W, N)); // split a run mid-flight
            if (t == 120) graph.updateConnections(GraphTestSupport.relative(origin, E, 5), GraphTestSupport.dirs(E, W));    // and merge it back
            solver.tick();
            if (t == 90) {
                for (GraphSegment<TransitPayload> s : graph.segments()) {
                    if (s.payload().forward().size() > 0) solver.setItemSpeed(s, true, 0, 0.4f);
                }
            }
            endTick();
            naiveBytes += 12L * client.size();
            assertSameWorld("tick " + t);
        }
        for (int t = 0; t < 400; t++) {
            solver.tick();
            endTick();
        }
        assertEquals(sent, delivered.size());
        assertTrue(lost.isEmpty());
        assertEquals(0, client.size(), "everything delivered is gone from the client too");
        assertTrue(messages > 0);
        // events instead of positions: a small fraction of streaming every item's coordinates every tick
        assertTrue(bytesSent * 10L < naiveBytes, bytesSent + " bytes against " + naiveBytes + " for streaming positions");
    }
}
