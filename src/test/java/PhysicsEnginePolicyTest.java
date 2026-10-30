import net.bichal.mods.celeris.physics.PhysicsJniTestAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which engine family Celeris tries: the Java kernel unless a mod asked for the native one
 * ({@code CelerisPhysics.allowNative}) or {@code -Dceleris.physics.engine} says otherwise, and
 * that property always has the last word.
 */
class PhysicsEnginePolicyTest {

    @Test
    void javaUnlessANativeKernelWasRequested() {
        assertEquals("java", PhysicsJniTestAccess.enginePolicy(null, false));
        assertEquals("auto", PhysicsJniTestAccess.enginePolicy(null, true));
    }

    @Test
    void thePropertyOverridesWhateverModsAsked() {
        assertEquals("java", PhysicsJniTestAccess.enginePolicy("java", true));
        assertEquals("off", PhysicsJniTestAccess.enginePolicy("off", true));
        assertEquals("auto", PhysicsJniTestAccess.enginePolicy("auto", false));
        assertEquals("jni", PhysicsJniTestAccess.enginePolicy("jni", false));
        assertEquals("ffm", PhysicsJniTestAccess.enginePolicy("FFM", false));
    }

    @Test
    void anUnknownValueCountsAsUnset() {
        assertEquals("java", PhysicsJniTestAccess.enginePolicy("fast", false));
        assertEquals("auto", PhysicsJniTestAccess.enginePolicy("fast", true));
    }
}
