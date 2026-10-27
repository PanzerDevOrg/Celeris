package net.bichal.mods.celeris.physics;

import net.bichal.mods.celeris.util.NativeLibraries;

/**
 * Calls into {@code libceleris_physics} (see {@code native/}) over JNI
 * ({@code native/src/cp_jni.cpp}). Same kernel and same results as the FFM
 * binding ({@code NativePhysicsKernel}), but JNI needs neither FFM nor any
 * launch flag, and is not blocked by {@code --enable-native-access}; that is
 * why it is tried first. One {@code nativeStep} call simulates a whole work
 * unit ({@link BodyLayout#MIN_UNIT} to {@link BodyLayout#CHUNK} bodies), so the
 * ~20 ns JNI transition is paid once per hundreds of bodies. A thread inside
 * the call is in native state, which GC safepoints do not wait for.
 *
 * <p>Loading the class loads the library and checks its ABI; it fails with an
 * {@link UnsatisfiedLinkError} (or an {@link ExceptionInInitializerError}
 * around it) when this platform has no library, and {@link CelerisPhysics}
 * then tries the next engine.
 */
final class JniPhysicsKernel {

    static final String[] ISA_NAMES = {"generic", "avx2", "avx512", "neon"};

    /** CP_BROADPHASE_STOP_AT_CAPACITY. */
    private static final int STOP_AT_CAPACITY = 1;

    static {
        System.load(NativeLibraries.extract(libraryFile()).toAbsolutePath().toString());
        int version = nativeAbiVersion();
        int layout = nativeLayoutSignature();
        if (version != BodyLayout.ABI_VERSION || layout != BodyLayout.signature()) {
            throw new IllegalStateException("celeris_physics ABI " + version + "/0x" + Integer.toHexString(layout)
                    + " does not match Java " + BodyLayout.ABI_VERSION + "/0x" + Integer.toHexString(BodyLayout.signature()));
        }
        RawMemory.probe();
        String isa = System.getProperty("celeris.physics.isa");
        if (isa != null) {
            for (int k = 0; k < ISA_NAMES.length; k++) {
                if (ISA_NAMES[k].equalsIgnoreCase(isa)) {
                    nativeForceIsa(k);
                }
            }
        }
    }

    private JniPhysicsKernel() {
    }

    static String libraryFile() {
        return NativeLibraries.isWindows() ? "celeris_physics.dll"
                : NativeLibraries.isMac() ? "libceleris_physics.dylib"
                : "libceleris_physics.so";
    }

    static String name() {
        return "native-jni-" + ISA_NAMES[activeIsa()];
    }

    static int activeIsa() {
        return nativeActiveIsa();
    }

    /** Pins an ISA (tests). Returns the ISA now active; unsupported requests are ignored. */
    static int forceIsa(int isa) {
        return nativeForceIsa(isa);
    }

    static int step(long slab, int capacity, long terrain, int begin, int end, int mode) {
        int deferred = nativeStep(slab, capacity, terrain, begin, end, mode);
        if (deferred < 0) {
            throw new IllegalArgumentException("cp_step rejected chunk [" + begin + ", " + end + ") of capacity " + capacity);
        }
        return deferred;
    }

    static int broadphase(long slab, int capacity, int count, long buckets, int bucketInts, double margin,
                          long pairs, int pairCapacity, boolean stopAtCapacity) {
        int found = nativeBroadphase(slab, capacity, count, buckets, bucketInts, margin, pairs, pairCapacity,
                stopAtCapacity ? STOP_AT_CAPACITY : 0);
        if (found < 0) {
            throw new IllegalArgumentException("cp_broadphase rejected count " + count + " of capacity " + capacity);
        }
        return found;
    }

    private static native int nativeAbiVersion();

    private static native int nativeLayoutSignature();

    private static native int nativeActiveIsa();

    private static native int nativeForceIsa(int isa);

    private static native int nativeStep(long bodies, int capacity, long terrain, int begin, int end, int mode);

    private static native int nativeBroadphase(long bodies, int capacity, int count, long buckets, int bucketInts,
                                               double margin, long pairs, int pairCapacity, int flags);
}
