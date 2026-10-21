package com.panzer.mods.celeris.api.transit;

import com.panzer.mods.celeris.core.topology.NodeId;

/**
 * Where a transported item is at a given moment, as a pure function of what a
 * client was told when the item started: the segment it is on, its progress
 * then, its speed and the tick it entered. No server state, no per-tick
 * updates: a renderer calls these every frame with
 * {@code tick + partialTick} and gets smooth motion, with nothing sent while
 * the item just keeps going.
 *
 * <p>The server advances progress by adding the speed once per tick; this
 * computes the same value in closed form, so the two agree to within float
 * rounding (about 1e-4 blocks after a thousand ticks). Everything that can
 * change the motion (leaving, changing segment or speed) arrives as a delta
 * ({@link TransitDelta}). An item held at the end of a segment by a full
 * receiver simply stays at the end until the delta that moves it arrives.
 *
 * <p>Items that keep their order and spacing need nothing more. To also
 * reproduce the spacing rule when an item catches the one ahead (speeds
 * differ), take a segment's items in the order they arrived there
 * ({@code ClientItemTable.arrivalAt}), head first, and pass each result to
 * {@link #limitBehind} for the next. The result can lead the server by at
 * most one tick of motion right after a head leaves, because the server frees
 * the item behind it on the following tick.
 *
 * <p>No Minecraft types: positions are packed {@link NodeId#raw()} longs.
 */
public final class TransitMotion {

    private TransitMotion() {
    }

    /**
     * Distance travelled along a segment {@code length} blocks long, from its
     * entry end, at time {@code now} (in ticks, fractional for frame
     * interpolation). Clamped to {@code [0, length]}; a time before
     * {@code entryTick} gives {@code startProgress}.
     */
    public static float progress(float startProgress, float speed, long entryTick, double now, float length) {
        double elapsed = now - entryTick;
        if (elapsed < 0d) {
            elapsed = 0d;
        }
        float p = startProgress + speed * (float) elapsed;
        if (!(p >= 0f)) {
            return 0f; // also catches NaN
        }
        return p > length ? length : p;
    }

    /**
     * Applies the spacing rule: an item cannot be closer than {@code spacing} to
     * the one ahead of it (whose own, already limited, progress is
     * {@code aheadProgress}). Walk a segment's items from the head backwards
     * and feed each result into the next call, exactly as the server does.
     */
    public static float limitBehind(float progress, float aheadProgress, float spacing) {
        float limit = aheadProgress - spacing;
        if (limit < 0f) {
            limit = 0f;
        }
        return progress > limit ? limit : progress;
    }

    /**
     * Writes the world position, at block centres, of the point {@code progress}
     * blocks along a segment into {@code out[0..2]} (x, y, z), interpolating
     * between consecutive block positions.
     *
     * @param positions packed block positions of the segment, {@code positions[0]} being its A end
     * @param count     how many entries of {@code positions} are valid (at least 1)
     * @param forward   true for an item travelling from the A end to the B end, false for the way back
     */
    public static void position(long[] positions, int count, boolean forward, float progress, float[] out) {
        int edges = count - 1;
        if (edges <= 0) {
            long only = positions[0];
            out[0] = NodeId.xOf(only) + 0.5f;
            out[1] = NodeId.yOf(only) + 0.5f;
            out[2] = NodeId.zOf(only) + 0.5f;
            return;
        }
        float s = forward ? progress : edges - progress;
        if (!(s >= 0f)) {
            s = 0f;
        } else if (s > edges) {
            s = edges;
        }
        int i = Math.min((int) s, edges - 1);
        float t = s - i;
        long a = positions[i];
        long b = positions[i + 1];
        out[0] = lerp(NodeId.xOf(a), NodeId.xOf(b), t) + 0.5f;
        out[1] = lerp(NodeId.yOf(a), NodeId.yOf(b), t) + 0.5f;
        out[2] = lerp(NodeId.zOf(a), NodeId.zOf(b), t) + 0.5f;
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
