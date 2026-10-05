package com.panzer.mods.celeris.physics;

/**
 * Lets the tests (default package, see src/test/java) build factories for a
 * specific kernel instead of the auto-selected one. Not API.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class PhysicsTestAccess {

    private PhysicsTestAccess() {
    }

    /** The scalar Java reference kernel, optionally with the Vector API streaming passes. */
    public static PhysicsFactory javaFactory(boolean simd) {
        StreamKernel streams = simd ? SegmentPhysicsFactory.loadStreams() : null;
        if (simd && streams == null) {
            throw new IllegalStateException("jdk.incubator.vector not available");
        }
        return new SegmentPhysicsFactory(new ScalarPhysicsKernel(streams));
    }

    /** The native kernel; throws if the library for this platform is not on the classpath. */
    public static PhysicsFactory nativeFactory() {
        return new SegmentPhysicsFactory(new NativePhysicsKernel());
    }

    /** ISAs the native kernel can run on this CPU, by name. Restores the default afterwards. */
    public static String[] nativeIsas(PhysicsFactory nativeFactory) {
        NativePhysicsKernel k = (NativePhysicsKernel) ((SegmentPhysicsFactory) nativeFactory).kernel();
        int active = k.activeIsa();
        StringBuilder out = new StringBuilder();
        for (int isa = 0; isa < NativePhysicsKernel.ISA_NAMES.length; isa++) {
            if (k.forceIsa(isa) == isa) {
                out.append(out.length() == 0 ? "" : ",").append(NativePhysicsKernel.ISA_NAMES[isa]);
            }
        }
        k.forceIsa(active);
        return out.toString().split(",");
    }

    /** Pins the native ISA by name; returns the active ISA name. */
    public static String forceNativeIsa(PhysicsFactory nativeFactory, String isa) {
        NativePhysicsKernel k = (NativePhysicsKernel) ((SegmentPhysicsFactory) nativeFactory).kernel();
        for (int i = 0; i < NativePhysicsKernel.ISA_NAMES.length; i++) {
            if (NativePhysicsKernel.ISA_NAMES[i].equals(isa)) {
                k.forceIsa(i);
            }
        }
        return NativePhysicsKernel.ISA_NAMES[k.activeIsa()];
    }

    /** Raw copy of a batch's slab, for bit-exact comparisons. */
    public static byte[] snapshot(BodyBatch batch) {
        return ((SegmentBodyBatch) batch).slab.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }
}
