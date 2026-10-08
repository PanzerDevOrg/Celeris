import com.panzer.mods.celeris.physics.BodyBatch;
import com.panzer.mods.celeris.physics.BodyFlags;
import com.panzer.mods.celeris.physics.BodyParams;
import com.panzer.mods.celeris.physics.PhysicsFactory;
import com.panzer.mods.celeris.physics.PhysicsMode;
import com.panzer.mods.celeris.physics.PhysicsTestAccess;
import com.panzer.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inputs of native/test/cp_fuzz.cpp on the Java kernel (and on the native
 * one, every ISA, when it is built): NaN, +-Inf, 1e300, +-3e9 and huge
 * velocities in every field, plus unloaded sections. A hostile body must come
 * back deferred and unmoved, without an exception, and without changing what
 * happens to the other bodies; Java and native must agree bit for bit.
 */
class PhysicsHostileInputTest {

    private static final int N = 32;
    private static final double[] HOSTILE = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e300, -1e300, 3e9, -3e9, 2147483648.0, -2147483649.0, 1e6, -1e6, 5e7, 2e9, -2e9, 1e8 + 1, 1e7 + 1};
    /** Fields: 0-2 position, 3-5 velocity, 6 width, 7 height. */
    private static final int FIELDS = 8;

    private record Kernel(String name, PhysicsFactory factory) {
    }

    private static List<Kernel> kernels() {
        List<Kernel> out = new ArrayList<>();
        out.add(new Kernel("java", PhysicsTestAccess.javaFactory()));
        try {
            PhysicsFactory nat = PhysicsTestAccess.nativeFactory();
            for (String isa : PhysicsTestAccess.nativeIsas(nat)) {
                out.add(new Kernel("native-" + isa, nat));
            }
        } catch (Throwable notBuilt) {
            // Java kernel only: the native library is not built for this platform.
        }
        return out;
    }

    private static void select(Kernel k) {
        if (k.name.startsWith("native-")) {
            PhysicsTestAccess.forceNativeIsa(k.factory, k.name.substring("native-".length()));
        }
    }

    /** Floor world with the section around the origin unloaded (COMPLEX). */
    private static TerrainView world(PhysicsFactory f) {
        TerrainView t = PhysicsTestSupport.floorWorld(f);
        t.clearSection(1, 0, 1);
        return t;
    }

    private static void fill(BodyBatch b) {
        for (int i = 0; i < N; i++) {
            b.add(BodyParams.ITEM, 0.25F, 0.25F, -8.0 + (i % 8) * 4.1, 6.0 + i * 0.37, -8.0 + (i / 8) * 9.3,
                    (i % 3 - 1) * 0.3, 0.1, ((i + 1) % 3 - 1) * 0.3, false, i & 3);
        }
    }

    private static void setField(BodyBatch b, int slot, int field, double v) {
        switch (field) {
            case 0 -> b.setPosition(slot, v, b.y(slot), b.z(slot));
            case 1 -> b.setPosition(slot, b.x(slot), v, b.z(slot));
            case 2 -> b.setPosition(slot, b.x(slot), b.y(slot), v);
            case 3 -> b.setVelocity(slot, v, b.vy(slot), b.vz(slot));
            case 4 -> b.setVelocity(slot, b.vx(slot), v, b.vz(slot));
            case 5 -> b.setVelocity(slot, b.vx(slot), b.vy(slot), v);
            case 6 -> b.setDimensions(slot, (float) v, 0.25F);
            default -> b.setDimensions(slot, 0.25F, (float) v);
        }
    }

    private static boolean outOfRange(BodyBatch b, int slot, int field) {
        double v = switch (field) {
            case 0 -> b.x(slot);
            case 1 -> b.y(slot);
            case 2 -> b.z(slot);
            case 3 -> b.vx(slot);
            case 4 -> b.vy(slot) - 0.04; // after gravity, as the kernel sees it
            case 5 -> b.vz(slot);
            case 6 -> b.halfWidth(slot);
            default -> b.height(slot);
        };
        double limit = field <= 2 ? 1e8 : field <= 5 ? 1e7 : 1e4;
        return !(Math.abs(v) <= limit);
    }

    private static long[] snapshot(BodyBatch b) {
        long[] s = new long[N * 7];
        for (int i = 0; i < N; i++) {
            s[7 * i] = Double.doubleToRawLongBits(b.x(i));
            s[7 * i + 1] = Double.doubleToRawLongBits(b.y(i));
            s[7 * i + 2] = Double.doubleToRawLongBits(b.z(i));
            s[7 * i + 3] = Double.doubleToRawLongBits(b.vx(i));
            s[7 * i + 4] = Double.doubleToRawLongBits(b.vy(i));
            s[7 * i + 5] = Double.doubleToRawLongBits(b.vz(i));
            s[7 * i + 6] = b.flags(i);
        }
        return s;
    }

    @Test
    void hostileBodiesAreDeferredAndIsolated() {
        for (Kernel k : kernels()) {
            select(k);
            try (TerrainView t = world(k.factory);
                 BodyBatch clean = k.factory.newBatch(N, PhysicsMode.VANILLA, 64)) {
                fill(clean);
                clean.step(t, 0);
                long[] ref = snapshot(clean);
                int victim = 0;
                for (double v : HOSTILE) {
                    for (int field = 0; field < FIELDS; field++, victim = (victim + 1) % N) {
                        try (BodyBatch b = k.factory.newBatch(N, PhysicsMode.VANILLA, 64)) {
                            fill(b);
                            setField(b, victim, field, v);
                            boolean hostile = outOfRange(b, victim, field);
                            double x = b.x(victim), y = b.y(victim), z = b.z(victim);
                            String what = k.name + ": field " + field + " = " + v;
                            int deferred = assertDoesNotThrow(() -> b.step(t, 0), what);
                            assertTrue(deferred >= 0 && deferred <= N, what);
                            if (hostile) {
                                assertNotEquals(0, b.flags(victim) & BodyFlags.DEFERRED, what + " not deferred");
                                assertEquals(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(b.x(victim)), what);
                                assertEquals(Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(b.y(victim)), what);
                                assertEquals(Double.doubleToRawLongBits(z), Double.doubleToRawLongBits(b.z(victim)), what);
                            }
                            long[] got = snapshot(b);
                            for (int i = 0; i < N; i++) {
                                if (i == victim) {
                                    continue;
                                }
                                for (int c = 0; c < 7; c++) {
                                    assertEquals(ref[7 * i + c], got[7 * i + c], what + ": body " + i + " value " + c);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void hugeSpansAreDeferred() {
        // Once (2^22, 2^21, 2^21) blocks wrapped the cell count to 0: an
        // ArrayIndexOutOfBoundsException in Java, a stack overflow natively.
        double[] speeds = {1e6, 4194302.0, 2097150.0, 1e7, 2e9};
        for (Kernel k : kernels()) {
            select(k);
            try (TerrainView t = world(k.factory)) {
                for (double a : speeds) {
                    for (double b : speeds) {
                        for (double c : speeds) {
                            try (BodyBatch batch = k.factory.newBatch(16, PhysicsMode.FUSED, 0)) {
                                batch.add(BodyParams.ITEM, 0.25F, 0.25F, 4.5, 8.0, 4.5, a, -b, c, false, 0);
                                String what = k.name + " (" + a + ", " + -b + ", " + c + ")";
                                assertEquals(1, assertDoesNotThrow(() -> batch.step(t, 0), what), what);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void allHostileSlabsMatchAcrossKernels() {
        List<Kernel> all = kernels();
        for (double v : HOSTILE) {
            long[] first = null;
            int[] firstPairs = null;
            for (Kernel k : all) {
                select(k);
                try (TerrainView t = world(k.factory);
                     BodyBatch b = k.factory.newBatch(N, PhysicsMode.VANILLA, 64)) {
                    fill(b);
                    for (int i = 0; i < N; i++) {
                        setField(b, i, i % FIELDS, v);
                    }
                    for (int tick = 0; tick < 4; tick++) {
                        assertDoesNotThrow(() -> b.step(t, 0), k.name + " " + v);
                    }
                    int[] pairs = {b.broadphase(0.5, false), b.broadphase(0.5)};
                    assertTrue(pairs[0] >= 0 && pairs[1] >= 0, k.name + " " + v);
                    long[] s = snapshot(b);
                    if (first == null) {
                        first = s;
                        firstPairs = pairs;
                    } else {
                        assertArrayEquals(first, s, k.name + " differs from java for " + v);
                        assertArrayEquals(firstPairs, pairs, k.name + " broadphase differs from java for " + v);
                    }
                }
            }
        }
    }
}
