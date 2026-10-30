package net.bichal.mods.celeris.physics;

/**
 * Lets the tests (default package, see src/test/java) pick the JNI engine and
 * pin its ISA. Not API.
 */
public final class PhysicsJniTestAccess {

    private PhysicsJniTestAccess() {
    }

    /** The JNI engine; throws if the library for this platform is not on the classpath. */
    public static PhysicsFactory jniFactory() {
        return new JniPhysicsFactory();
    }

    /** ISAs the native kernel can run on this CPU, by name. Restores the default afterwards. */
    public static String[] jniIsas() {
        int active = JniPhysicsKernel.activeIsa();
        StringBuilder out = new StringBuilder();
        for (int isa = 0; isa < JniPhysicsKernel.ISA_NAMES.length; isa++) {
            if (JniPhysicsKernel.forceIsa(isa) == isa) {
                out.append(out.length() == 0 ? "" : ",").append(JniPhysicsKernel.ISA_NAMES[isa]);
            }
        }
        JniPhysicsKernel.forceIsa(active);
        return out.toString().split(",");
    }

    /** Pins the native ISA by name; returns the active ISA name. */
    public static String forceJniIsa(String isa) {
        for (int i = 0; i < JniPhysicsKernel.ISA_NAMES.length; i++) {
            if (JniPhysicsKernel.ISA_NAMES[i].equals(isa)) {
                JniPhysicsKernel.forceIsa(i);
            }
        }
        return JniPhysicsKernel.ISA_NAMES[JniPhysicsKernel.activeIsa()];
    }

    /** The engine family {@code CelerisPhysics} would try for a property value and a native request. */
    public static String enginePolicy(String property, boolean nativeRequested) {
        return CelerisPhysics.policy(property, nativeRequested);
    }
}
