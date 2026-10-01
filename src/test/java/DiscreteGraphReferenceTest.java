import com.panzer.mods.celeris.core.dirty.LongRingDirtyQueue;
import com.panzer.mods.celeris.core.memory.backend.HeapMemoryBackend;
import com.panzer.mods.celeris.core.topology.ConnectionMask;
import com.panzer.mods.celeris.core.topology.NetworkTopologyImpl;
import com.panzer.mods.celeris.core.topology.NodeId;
import com.panzer.mods.celeris.graph.discrete.DiscreteGraphImpl;
import com.panzer.mods.celeris.graph.discrete.DiscreteNodeStore;
import com.panzer.mods.celeris.graph.discrete.PropagationBudget;
import com.panzer.mods.celeris.pipeline.discrete.EventDrivenPipelineSolverImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal reference test for the discrete/event-driven paradigm: a 20-node
 * straight line fed a level-15 source at one end must attenuate by exactly
 * one level per hop and settle in a single tick() call (one straight run,
 * no fan-out, discovered fully within PropagationBudget.standard()).
 */
@SuppressWarnings("unused")
class DiscreteGraphReferenceTest {

    // Direction ordinal order, matching BranchlessTables: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST.
    private static final int WEST = 4;
    private static final int EAST = 5;

    private DiscreteGraphImpl newGraph() {
        NetworkTopologyImpl topology = new NetworkTopologyImpl();
        DiscreteNodeStore nodes = new DiscreteNodeStore(new HeapMemoryBackend(), 64);
        LongRingDirtyQueue dirtyQueue = new LongRingDirtyQueue();
        return new DiscreteGraphImpl(topology, nodes, dirtyQueue, PropagationBudget.standard());
    }

    @Test
    void twentyNodeLineAttenuatesFifteenToZeroAndStaysFlatBeyondRange() {
        DiscreteGraphImpl graph = newGraph();

        NodeId[] line = new NodeId[20];
        for (int i = 0; i < line.length; i++) {
            line[i] = NodeId.pack(i, 0, 0);
        }
        for (int i = 0; i < line.length; i++) {
            int bits = 0;
            if (i > 0) bits |= 1 << WEST;
            if (i < line.length - 1) bits |= 1 << EAST;
            graph.addNode(line[i], ConnectionMask.of(bits), i == 0);
        }

        EventDrivenPipelineSolverImpl pipeline = new EventDrivenPipelineSolverImpl(graph.solver(), graph.dirtyQueue());

        graph.setSourceLevel(line[0], 15);
        pipeline.tick();

        for (int i = 0; i < 16; i++) {
            assertEquals(15 - i, graph.signalLevelAt(line[i]), "Node at distance " + i + " should read 15 - " + i);
        }
        for (int i = 16; i < line.length; i++) {
            assertEquals(0, graph.signalLevelAt(line[i]), "Node at distance " + i + " (beyond range 15) should read 0");
        }

        assertTrue(pipeline.isIdle(), "Network should settle within a single tick() call");
    }

    @Test
    void idleDirtyQueueShortCircuitsPropagateToZeroWork() {
        DiscreteGraphImpl graph = newGraph();
        NodeId a = NodeId.pack(0, 0, 0);
        graph.addNode(a, ConnectionMask.NONE, true);
        graph.solver().propagate(); // drains the initial dirty mark from addNode

        assertTrue(graph.dirtyQueue().isIdle());
        assertEquals(0, graph.solver().propagate(), "An idle dirty queue must short-circuit propagate() to zero work");
    }
}
