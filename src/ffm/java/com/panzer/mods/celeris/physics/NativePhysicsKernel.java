package com.panzer.mods.celeris.physics;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Downcalls into {@code libceleris_physics} (see {@code native/}). One
 * {@code cp_step} call simulates a whole {@link BodyLayout#CHUNK} -- gravity,
 * collision and drag fused while the chunk sits in L1/L2 -- so the
 * fixed cost of a downcall (a few ns) is paid once per thousand bodies.
 *
 * <p>Both hot entry points are linked as critical/trivial: no thread-state
 * transition and no safepoint poll. A chunk call runs tens of microseconds,
 * which is the longest a GC safepoint can be held up by it; {@link
 * BodyLayout#CHUNK} bounds that.
 */
@SuppressWarnings({"Since15", "preview", "RedundantSuppression"})
final class NativePhysicsKernel implements PhysicsKernel {

    static final String[] ISA_NAMES = {"generic", "avx2", "avx512", "neon"};

    private static final Linker LINKER = Linker.nativeLinker();
    //? >=25 {
    //private static final Linker.Option[] CRITICAL = { Linker.Option.critical(false) };
    //?} else
    private static final Linker.Option[] CRITICAL = { Linker.Option.isTrivial() };

    private final MethodHandle step;
    private final MethodHandle broadphase;
    private final MethodHandle activeIsa;
    private final MethodHandle forceIsa;

    NativePhysicsKernel() {
        SymbolLookup lib = SymbolLookup.libraryLookup(extractLibrary(), Arena.global());
        MethodHandle abi = LINKER.downcallHandle(lib.find("cp_abi_version").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        MethodHandle signature = LINKER.downcallHandle(lib.find("cp_layout_signature").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        activeIsa = LINKER.downcallHandle(lib.find("cp_active_isa").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT), CRITICAL);
        forceIsa = LINKER.downcallHandle(lib.find("cp_force_isa").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        step = LINKER.downcallHandle(lib.find("cp_step").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                CRITICAL);
        broadphase = LINKER.downcallHandle(lib.find("cp_broadphase").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT),
                CRITICAL);
        try {
            int version = (int) abi.invokeExact();
            int layout = (int) signature.invokeExact();
            if (version != BodyLayout.ABI_VERSION || layout != BodyLayout.signature()) {
                throw new IllegalStateException("celeris_physics ABI " + version + "/0x" + Integer.toHexString(layout)
                        + " does not match Java " + BodyLayout.ABI_VERSION + "/0x" + Integer.toHexString(BodyLayout.signature()));
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        String isa = System.getProperty("celeris.physics.isa");
        if (isa != null) {
            for (int k = 0; k < ISA_NAMES.length; k++) {
                if (ISA_NAMES[k].equalsIgnoreCase(isa)) {
                    forceIsa(k);
                }
            }
        }
    }

    @Override
    public String name() {
        return "native-" + ISA_NAMES[activeIsa()];
    }

    int activeIsa() {
        try {
            return (int) activeIsa.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Pins an ISA (tests). Returns the ISA now active; unsupported requests are ignored. */
    int forceIsa(int isa) {
        try {
            return (int) forceIsa.invokeExact(isa);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public int step(MemorySegment slab, int capacity, SegmentTerrain terrain, int begin, int end, int mode) {
        int deferred;
        try {
            deferred = (int) step.invokeExact(slab, capacity, terrain.header, begin, end, mode);
        } catch (Throwable t) {
            throw new IllegalStateException("cp_step failed", t);
        }
        if (deferred < 0) {
            throw new IllegalArgumentException("cp_step rejected chunk [" + begin + ", " + end + ") of capacity " + capacity);
        }
        return deferred;
    }

    @Override
    public int broadphase(MemorySegment slab, int capacity, int count, MemorySegment buckets, double margin,
                          MemorySegment pairs, int pairCapacity) {
        try {
            return (int) broadphase.invokeExact(slab, capacity, count, buckets, margin, pairs, pairCapacity);
        } catch (Throwable t) {
            throw new IllegalStateException("cp_broadphase failed", t);
        }
    }

    private static Path extractLibrary() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");
        String file;
        String platform;
        if (os.contains("win")) {
            platform = aarch64 ? "windows-aarch64" : "windows-x86_64";
            file = "celeris_physics.dll";
        } else if (os.contains("mac")) {
            platform = aarch64 ? "macos-aarch64" : "macos-x86_64";
            file = "libceleris_physics.dylib";
        } else {
            platform = aarch64 ? "linux-aarch64" : "linux-x86_64";
            file = "libceleris_physics.so";
        }
        String resource = "natives/" + platform + "/" + file;
        try (InputStream in = NativePhysicsKernel.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("no bundled " + resource);
            }
            Path dir = Files.createTempDirectory("celeris-physics-");
            dir.toFile().deleteOnExit();
            Path out = dir.resolve(file);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("failed extracting " + resource, e);
        }
    }
}
