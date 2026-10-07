package bench;

import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.ZstdJniTestAccess;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend;

/** Celeris's backend and zstd binding, picked by -Dbench.binding=jni|ffm (ffm needs --enable-preview on Java 21). */
final class Celeris {
    private Celeris() {
    }

    static boolean ffm() {
        return "ffm".equals(System.getProperty("bench.binding", "jni"));
    }

    static MemoryBackend backend() {
        if (!ffm()) {
            return new UnsafeMemoryBackend();
        }
        try {
            return (MemoryBackend) Class.forName("com.panzer.mods.celeris.core.memory.backend.FfmMemoryBackend")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static CompressionCodec codec() {
        if (!ffm()) {
            return ZstdJniTestAccess.create();
        }
        try {
            return (CompressionCodec) Class.forName("com.panzer.mods.celeris.core.memory.ZstdCompressionCodecTestAccess")
                    .getMethod("create").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
