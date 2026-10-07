import com.panzer.mods.celeris.util.NativeLibrariesTestAccess;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Extraction into the content-addressed cache, against a throwaway cache root. */
@SuppressWarnings("unused")
class NativeLibrariesTest {

    /** Any resource on the test classpath will do; this class's own bytecode always is. */
    private static final String RESOURCE = "NativeLibrariesTest.class";

    private static byte[] expected() throws IOException {
        try (InputStream in = NativeLibrariesTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "test resource missing");
            return in.readAllBytes();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void extractsOnceAndReuses() throws IOException {
        Path root = Files.createTempDirectory("celeris-natives-test");
        try {
            Path first = NativeLibrariesTestAccess.extract(RESOURCE, "lib.bin", root);
            assertArrayEquals(expected(), Files.readAllBytes(first));
            assertTrue(first.startsWith(root), first + " not under the cache root");
            FileTime written = FileTime.fromMillis(1_000_000L);
            Files.setLastModifiedTime(first, written);

            Path second = NativeLibrariesTestAccess.extract(RESOURCE, "lib.bin", root);
            assertEquals(first, second);
            assertEquals(written, Files.getLastModifiedTime(second), "an intact cached copy must not be rewritten");
            try (Stream<Path> files = Files.list(first.getParent())) {
                assertEquals(1, files.count(), "no temporary files left behind");
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void replacesATamperedCopy() throws IOException {
        Path root = Files.createTempDirectory("celeris-natives-test");
        try {
            Path path = NativeLibrariesTestAccess.extract(RESOURCE, "lib.bin", root);
            byte[] bad = expected().clone();
            bad[bad.length / 2] ^= 0x5A;
            Files.write(path, bad);

            Path again = NativeLibrariesTestAccess.extract(RESOURCE, "lib.bin", root);
            assertEquals(path, again);
            assertArrayEquals(expected(), Files.readAllBytes(again));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void fallsBackWhenTheCacheIsUnusable() throws IOException {
        Path blocker = Files.createTempFile("celeris-natives-blocker", ".txt");
        try {
            // A regular file where the cache directory should be: nothing can be created under it.
            Path path = NativeLibrariesTestAccess.extract(RESOURCE, "lib.bin", blocker);
            assertFalse(path.startsWith(blocker));
            assertArrayEquals(expected(), Files.readAllBytes(path));
        } finally {
            Files.deleteIfExists(blocker);
        }
    }

    @Test
    void missingResourceIsReported() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NativeLibrariesTestAccess.extract("natives/none/missing.so", "missing.so",
                        Path.of(System.getProperty("java.io.tmpdir"))));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }
}
