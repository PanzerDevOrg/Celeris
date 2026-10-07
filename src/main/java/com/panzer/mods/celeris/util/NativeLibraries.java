package com.panzer.mods.celeris.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unpacks the native libraries bundled in Celeris's jar
 * ({@code natives/<os>-<arch>/<file>}) to a file the JVM can load.
 *
 * <p>Libraries go to a per-user cache directory named after the SHA-256 of
 * their bytes, so every later launch reuses the same file instead of writing
 * a fresh copy to the temp directory (a loaded DLL cannot be deleted on exit
 * on Windows, so per-launch copies used to pile up there). A cached file is
 * reused only if its bytes match the jar's exactly; new files are written to
 * a temporary name and moved into place, so a crash or a second game starting
 * at the same moment never leaves a half-written library behind. If the cache
 * directory is not writable, the library falls back to a private temporary
 * directory, as before.
 */
public final class NativeLibraries {

    private static final Logger LOGGER = LoggerFactory.getLogger("Celeris/Natives");
    private static final ConcurrentHashMap<String, Path> EXTRACTED = new ConcurrentHashMap<>();

    private NativeLibraries() {
    }

    /** {@code "<os>-<arch>"} as used in the jar ({@code linux-x86_64}, {@code windows-aarch64}, ...), or {@code null} if Celeris ships no natives for this CPU. */
    public static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            cpu = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            cpu = "aarch64";
        } else {
            return null;
        }
        String os = isWindows() ? "windows" : isMac() ? "macos" : "linux";
        return os + "-" + cpu;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Path of {@code natives/<platform>/<fileName>} from Celeris's jar,
     * unpacked once per JVM.
     *
     * @throws IllegalStateException if this platform has no such library or it cannot be written anywhere
     */
    public static Path extract(String fileName) {
        String platform = platform();
        if (platform == null) {
            throw new IllegalStateException("Celeris ships no native libraries for CPU architecture "
                    + System.getProperty("os.arch"));
        }
        String resource = "natives/" + platform + "/" + fileName;
        return EXTRACTED.computeIfAbsent(resource, r -> extract(r, fileName, cacheRoots()));
    }

    /** Extraction itself, with the cache roots passed in (tests use a temporary one). */
    static Path extract(String resource, String fileName, List<Path> cacheRoots) {
        byte[] bytes = readResource(resource);
        String hash = sha256(bytes).substring(0, 16);
        IOException failure = null;
        for (Path root : cacheRoots) {
            try {
                return materialize(root.resolve(hash), fileName, bytes);
            } catch (IOException | SecurityException e) {
                failure = e instanceof IOException io ? io : new IOException(e);
            }
        }
        try {
            Path dir = Files.createTempDirectory("celeris-natives-");
            dir.toFile().deleteOnExit();
            Path target = dir.resolve(fileName);
            Files.write(target, bytes);
            target.toFile().deleteOnExit();
            LOGGER.debug("Native cache unavailable ({}), using {}", failure, target);
            return target;
        } catch (IOException e) {
            if (failure != null) {
                e.addSuppressed(failure);
            }
            throw new UncheckedIOException("Celeris could not unpack " + resource, e);
        }
    }

    private static Path materialize(Path dir, String fileName, byte[] bytes) throws IOException {
        Path target = dir.resolve(fileName);
        if (matches(target, bytes)) {
            return target;
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, fileName, ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Windows refuses to replace a DLL another running game has loaded;
            // if that file is already the right one, it is the one to use.
            if (matches(target, bytes)) {
                return target;
            }
            throw e;
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!matches(target, bytes)) {
            throw new IOException("Unpacked library does not match the jar: " + target);
        }
        return target;
    }

    private static boolean matches(Path file, byte[] expected) {
        try {
            return Files.isRegularFile(file)
                    && Files.size(file) == expected.length
                    && Arrays.equals(Files.readAllBytes(file), expected);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = NativeLibraries.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled native library not found: " + resource
                        + " (this platform may not be supported, or the jar was built without it)");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading bundled native library " + resource, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK ships SHA-256
        }
    }

    /** Per-user cache locations, most specific first. */
    private static List<Path> cacheRoots() {
        List<Path> roots = new ArrayList<>(1);
        String home = System.getProperty("user.home");
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                roots.add(Path.of(local, "Celeris", "natives"));
            }
        } else if (isMac()) {
            if (home != null && !home.isBlank()) {
                roots.add(Path.of(home, "Library", "Caches", "Celeris", "natives"));
            }
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
                roots.add(Path.of(xdg, "celeris", "natives"));
            } else if (home != null && !home.isBlank()) {
                roots.add(Path.of(home, ".cache", "celeris", "natives"));
            }
        }
        return roots;
    }
}
