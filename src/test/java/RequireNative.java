import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Native tests are skipped where no library was built, except when the build says the
 * library is required (release gate: {@code REQUIRE_LIBRARIES=1} or
 * {@code -Dceleris.requireNative=true}); then a missing or unloadable library fails
 * instead of letting the gate pass with zero native tests run.
 */
final class RequireNative {

    private RequireNative() {
    }

    static boolean required() {
        return "1".equals(System.getenv("REQUIRE_LIBRARIES")) || Boolean.getBoolean("celeris.requireNative");
    }

    /** Skips the test, or fails it when native libraries are required. */
    static void skipOrFail(String reason) {
        if (required()) {
            fail("native library required but unavailable: " + reason);
        }
        assumeTrue(false, reason);
    }

    static void assumeOrFail(boolean available, String reason) {
        if (!available) {
            skipOrFail(reason);
        }
    }
}
