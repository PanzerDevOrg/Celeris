package com.panzer.mods.celeris.core.memory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Raw FFI bindings to the bundled native libzstd. Never referenced except
 * through {@link ZstdCompressionCodec}, and only after {@code CelerisRuntime}
 * has confirmed FFM works -- the static initializer below performs downcall
 * setup immediately on class load and throws if FFM (or the native library
 * for this platform) is unavailable, so merely loading this class on an
 * unsupported JVM is enough to crash. There is no pure-Java equivalent of
 * this class; the fallback lives entirely in {@code DeflateCompressionCodec}.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class ZstdCodec {

    private static final Linker LINKER = Linker.nativeLinker();
    //? >=25 {
    //private static final Linker.Option[] TRIVIAL = { Linker.Option.critical(true) };
    //?} else
    private static final Linker.Option[] TRIVIAL = { Linker.Option.isTrivial() };
    private static final SymbolLookup ZSTD;
    private static final MethodHandle COMPRESS_BOUND;
    private static final MethodHandle COMPRESS;
    private static final MethodHandle DECOMPRESS;
    private static final MethodHandle IS_ERROR;
    private static final MethodHandle GET_ERROR_NAME;

    static {
        ZSTD = SymbolLookup.libraryLookup(resolveZstdLibraryPath(), Arena.global());

        COMPRESS_BOUND = LINKER.downcallHandle(
                ZSTD.find("ZSTD_compressBound").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG)
        );

        COMPRESS = LINKER.downcallHandle(
                ZSTD.find("ZSTD_compress").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT)
        );

        DECOMPRESS = LINKER.downcallHandle(
                ZSTD.find("ZSTD_decompress").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );

        IS_ERROR = LINKER.downcallHandle(
                ZSTD.find("ZSTD_isError").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
                TRIVIAL
        );

        GET_ERROR_NAME = LINKER.downcallHandle(
                ZSTD.find("ZSTD_getErrorName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
        );
    }

    private ZstdCodec() {
    }

    private static String platformDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean isAarch64 = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            return "windows-x86_64";
        }
        if (os.contains("mac")) {
            return isAarch64 ? "macos-aarch64" : "macos-x86_64";
        }
        return isAarch64 ? "linux-aarch64" : "linux-x86_64";
    }

    private static String zstdFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "libzstd.dll";
        }
        if (os.contains("mac")) {
            return "libzstd.dylib";
        }
        return "libzstd.so.1";
    }

    private static Path resolveZstdLibraryPath() {
        String resourcePath = "natives/" + platformDirectory() + "/" + zstdFileName();

        try (java.io.InputStream in = ZstdCodec.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new NativeCallException(
                        "Bundled native library not found on classpath: " + resourcePath + " -- this platform/architecture may not be supported, or the mod jar was built without natives for it (check celeris.native.target / celeris.fatJar)",
                        null);
            }

            Path tempDir = Files.createTempDirectory("celeris-natives-");
            tempDir.toFile().deleteOnExit();
            Path extracted = tempDir.resolve(zstdFileName());
            Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (java.io.IOException e) {
            throw new NativeCallException("Failed extracting bundled native library: " + resourcePath, e);
        }
    }

    static long compressBound(long srcSize) {
        try {
            return (long) COMPRESS_BOUND.invokeExact(srcSize);
        } catch (Throwable t) {
            throw new NativeCallException("ZSTD_compressBound failed", t);
        }
    }

    static long compress(MemorySegment dst, long dstCapacity, MemorySegment src, long srcSize, int level) {
        try {
            long written = (long) COMPRESS.invokeExact(dst, dstCapacity, src, srcSize, level);
            if (isError(written)) {
                throw new NativeCallException("ZSTD_compress failed: " + errorName(written), null);
            }
            return written;
        } catch (Throwable t) {
            if (t instanceof NativeCallException nce) {
                throw nce;
            }
            throw new NativeCallException("ZSTD_compress failed", t);
        }
    }

    static long decompress(MemorySegment dst, long dstCapacity, MemorySegment src, long srcSize) {
        try {
            long written = (long) DECOMPRESS.invokeExact(dst, dstCapacity, src, srcSize);
            if (isError(written)) {
                throw new NativeCallException("ZSTD_decompress failed: " + errorName(written), null);
            }
            return written;
        } catch (Throwable t) {
            if (t instanceof NativeCallException nce) {
                throw nce;
            }
            throw new NativeCallException("ZSTD_decompress failed", t);
        }
    }

    private static boolean isError(long code) {
        try {
            int result = (int) IS_ERROR.invokeExact(code);
            return result != 0;
        } catch (Throwable t) {
            throw new NativeCallException("ZSTD_isError call failed", t);
        }
    }

    private static String errorName(long code) {
        try {
            MemorySegment namePtr = (MemorySegment) GET_ERROR_NAME.invokeExact(code);
            //? >=25 {
            //return namePtr.reinterpret(256L).getString(0L);
            //?} else
            return namePtr.reinterpret(256L).getUtf8String(0L);
        } catch (Throwable t) {
            return "unknown zstd error " + code;
        }
    }

    static final class NativeCallException extends RuntimeException {
        NativeCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
