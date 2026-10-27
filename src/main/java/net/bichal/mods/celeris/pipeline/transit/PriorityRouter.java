package net.bichal.mods.celeris.pipeline.transit;

/**
 * Sends each item to the candidate exit with the highest priority; ties go to
 * the lowest direction index. Stateless.
 */
public final class PriorityRouter implements JunctionRouter {

    /** Priority of leaving {@code nodeRaw} through {@code direction}; higher wins. */
    @FunctionalInterface
    public interface Priorities {
        int priority(long nodeRaw, int direction);
    }

    private final Priorities priorities;

    public PriorityRouter(Priorities priorities) {
        this.priorities = priorities;
    }

    @Override
    public int route(long nodeRaw, int fromDir, int handle, int candidates) {
        int best = -1;
        int bestPriority = Integer.MIN_VALUE;
        for (int bits = candidates; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            int p = priorities.priority(nodeRaw, dir);
            if (best < 0 || p > bestPriority) {
                best = dir;
                bestPriority = p;
            }
        }
        return best;
    }
}
