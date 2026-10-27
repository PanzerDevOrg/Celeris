package net.bichal.mods.celeris.core.spatial;

/**
 * Three nearest points to a query among a small set, on flat {@code int[]} coordinates.
 *
 * <p>Squared distances are plain {@code int} arithmetic (callers keep coordinates within about
 * +-23000 of the query so nothing overflows). Points are visited in index order and a point that
 * ties an earlier one ranks <em>before</em> it (comparisons are {@code >=}), which is the order
 * the vanilla aquifer search uses; results are therefore identical to that loop.
 */
public final class Nearest3 {

    private Nearest3() {
    }

    /**
     * Index of the nearest, second and third nearest of the first {@code n} points
     * ({@code n >= 3}, and {@code n <= 255}), packed as {@code first | second << 8 | third << 16}.
     */
    public static int search(int[] xs, int[] ys, int[] zs, int n, int x, int y, int z) {
        int d1 = Integer.MAX_VALUE, d2 = Integer.MAX_VALUE, d3 = Integer.MAX_VALUE;
        int n1 = 0, n2 = 0, n3 = 0;
        for (int i = 0; i < n; i++) {
            int dx = xs[i] - x, dy = ys[i] - y, dz = zs[i] - z;
            int d = dx * dx + dy * dy + dz * dz;
            if (d1 >= d) {
                n3 = n2;
                n2 = n1;
                n1 = i;
                d3 = d2;
                d2 = d1;
                d1 = d;
            } else if (d2 >= d) {
                n3 = n2;
                n2 = i;
                d3 = d2;
                d2 = d;
            } else if (d3 >= d) {
                n3 = i;
                d3 = d;
            }
        }
        return n1 | n2 << 8 | n3 << 16;
    }

    /** Squared distance from point {@code i} to the query, as {@link #search} computes it. */
    public static int distanceSq(int[] xs, int[] ys, int[] zs, int i, int x, int y, int z) {
        int dx = xs[i] - x, dy = ys[i] - y, dz = zs[i] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static int first(int packed) {
        return packed & 0xFF;
    }

    public static int second(int packed) {
        return packed >>> 8 & 0xFF;
    }

    public static int third(int packed) {
        return packed >>> 16 & 0xFF;
    }
}
