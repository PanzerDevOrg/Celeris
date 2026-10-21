package com.panzer.mods.celeris.pipeline.transit;

/**
 * Restricts the candidate exits to those whose filter accepts the item, then
 * lets another router choose among them. An item no exit accepts waits.
 */
public final class FilterRouter implements JunctionRouter {

    @FunctionalInterface
    public interface ExitFilter {
        boolean accepts(long nodeRaw, int direction, int handle);
    }

    private final ExitFilter filter;
    private final JunctionRouter next;

    public FilterRouter(ExitFilter filter, JunctionRouter next) {
        this.filter = filter;
        this.next = next;
    }

    @Override
    public int route(long nodeRaw, int fromDir, int handle, int candidates) {
        int allowed = 0;
        for (int bits = candidates; bits != 0; bits &= bits - 1) {
            int dir = Integer.numberOfTrailingZeros(bits);
            if (filter.accepts(nodeRaw, dir, handle)) {
                allowed |= 1 << dir;
            }
        }
        return allowed == 0 ? -1 : next.route(nodeRaw, fromDir, handle, allowed);
    }

    @Override
    public void committed(long nodeRaw, int direction, int handle) {
        next.committed(nodeRaw, direction, handle);
    }
}
