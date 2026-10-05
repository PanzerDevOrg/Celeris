package com.panzer.mods.celeris.physics;

/**
 * Lets the tests (default package, see src/test/java) pick a specific engine
 * instead of the auto-selected one. Not API.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
public final class PhysicsTestAccess {

    private PhysicsTestAccess() {
    }

    /** The pure-Java reference engine (heap arrays). */
    public static PhysicsFactory javaFactory() {
        return new HeapPhysicsFactory();
    }

    /** The native engine; throws if the library for this platform is not on the classpath. */
    public static PhysicsFactory nativeFactory() {
        return new SegmentPhysicsFactory();
    }

    /** ISAs the native kernel can run on this CPU, by name. Restores the default afterwards. */
    public static String[] nativeIsas(PhysicsFactory nativeFactory) {
        NativePhysicsKernel k = kernel(nativeFactory);
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
        NativePhysicsKernel k = kernel(nativeFactory);
        for (int i = 0; i < NativePhysicsKernel.ISA_NAMES.length; i++) {
            if (NativePhysicsKernel.ISA_NAMES[i].equals(isa)) {
                k.forceIsa(i);
            }
        }
        return NativePhysicsKernel.ISA_NAMES[k.activeIsa()];
    }

    private static NativePhysicsKernel kernel(PhysicsFactory nativeFactory) {
        return (NativePhysicsKernel) ((SegmentPhysicsFactory) nativeFactory).kernel();
    }
}
