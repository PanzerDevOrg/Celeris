package bench;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.panzer.mods.celeris.core.memory.CompressionCodec;
import com.panzer.mods.celeris.core.memory.ZstdJniTestAccess;
import com.panzer.mods.celeris.core.memory.backend.MemoryBackend;
import com.panzer.mods.celeris.core.memory.backend.UnsafeMemoryBackend;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One payload compressed and decompressed, per call, by each library at its
 * usual setting for game data: zstd level 3 (Celeris over JNI and over FFM,
 * zstd-jni with reused contexts on heap arrays and on direct buffers),
 * java.util.zip deflate levels 1 and 6, LZ4 fast. Every library reuses its
 * contexts across calls, as a real server would.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class CompressionBench {

    @Param({"3072", "38912", "1048576"})
    public int size;

    byte[] raw;
    // Celeris: off-heap source/destination, as its API takes them.
    MemoryBackend backend;
    CompressionCodec celeris;
    long cSrc, cPacked, cOut;
    long cPackedSize;
    // zstd-jni
    ZstdCompressCtx zc;
    ZstdDecompressCtx zd;
    byte[] zPacked, zOut;
    int zPackedSize;
    ByteBuffer dSrc, dPacked, dOut;
    int dPackedSize;
    // deflate
    Deflater deflater1, deflater6;
    Inflater inflater;
    byte[] fPacked, fScratch, fOut;
    int fPackedSize;
    // lz4
    LZ4Compressor lz4c;
    LZ4FastDecompressor lz4d;
    byte[] lPacked;
    int lPackedSize;

    @Setup
    public void setup() {
        raw = ChunkData.generate(size, 42);
        backend = Celeris.backend();
        celeris = Celeris.codec();
        long bound = celeris.compressBound(size);
        cSrc = backend.allocate(size, 8);
        cPacked = backend.allocate(bound, 8);
        cOut = backend.allocate(size, 8);
        backend.copyFromHeap(cSrc, 0, raw, 0, size);
        cPackedSize = celeris.compress(backend, cPacked, bound, cSrc, 0, size, 3);

        zc = new ZstdCompressCtx().setLevel(3);
        zd = new ZstdDecompressCtx();
        zPacked = new byte[(int) com.github.luben.zstd.Zstd.compressBound(size)];
        zOut = new byte[size];
        zPackedSize = zc.compressByteArray(zPacked, 0, zPacked.length, raw, 0, size);
        dSrc = ByteBuffer.allocateDirect(size);
        dSrc.put(raw).flip();
        dPacked = ByteBuffer.allocateDirect(zPacked.length);
        dOut = ByteBuffer.allocateDirect(size);
        dPackedSize = zc.compressDirectByteBuffer(dPacked, 0, dPacked.capacity(), dSrc, 0, size);

        deflater1 = new Deflater(1);
        deflater6 = new Deflater(6);
        inflater = new Inflater();
        fPacked = new byte[size + size / 10 + 1024];
        fScratch = new byte[fPacked.length];
        fOut = new byte[size];
        fPackedSize = deflate(deflater6);
        System.arraycopy(fScratch, 0, fPacked, 0, fPackedSize);

        LZ4Factory f = LZ4Factory.fastestInstance();
        lz4c = f.fastCompressor();
        lz4d = f.fastDecompressor();
        lPacked = new byte[lz4c.maxCompressedLength(size)];
        lPackedSize = lz4c.compress(raw, 0, size, lPacked, 0, lPacked.length);

        System.out.printf("%nratio at %d bytes: celeris zstd %.2f, zstd-jni %.2f, deflate6 %.2f, deflate1 %.2f, lz4 %.2f%n",
                size, (double) size / cPackedSize, (double) size / zPackedSize, (double) size / fPackedSize,
                (double) size / deflate(deflater1), (double) size / lPackedSize);
    }

    @TearDown
    public void tearDown() {
        backend.free(cSrc);
        backend.free(cPacked);
        backend.free(cOut);
        zc.close();
        zd.close();
        deflater1.end();
        deflater6.end();
        inflater.end();
    }

    private int deflate(Deflater d) {
        d.reset();
        d.setInput(raw, 0, size);
        d.finish();
        int n = 0;
        while (!d.finished()) {
            n += d.deflate(fScratch, n, fScratch.length - n);
        }
        return n;
    }

    @Benchmark
    public long celerisZstdCompress() {
        return celeris.compress(backend, cPacked, celeris.compressBound(size), cSrc, 0, size, 3);
    }

    @Benchmark
    public long celerisZstdDecompress() {
        return celeris.decompress(backend, cOut, size, cPacked, 0, cPackedSize);
    }

    @Benchmark
    public int zstdJniCompress() {
        return zc.compressByteArray(zPacked, 0, zPacked.length, raw, 0, size);
    }

    @Benchmark
    public int zstdJniDecompress() {
        return zd.decompressByteArray(zOut, 0, size, zPacked, 0, zPackedSize);
    }

    @Benchmark
    public int zstdJniDirectCompress() {
        return zc.compressDirectByteBuffer(dPacked, 0, dPacked.capacity(), dSrc, 0, size);
    }

    @Benchmark
    public int zstdJniDirectDecompress() {
        return zd.decompressDirectByteBuffer(dOut, 0, size, dPacked, 0, dPackedSize);
    }

    @Benchmark
    public int deflate1Compress() {
        return deflate(deflater1);
    }

    @Benchmark
    public int deflate6Compress() {
        return deflate(deflater6);
    }

    @Benchmark
    public int inflateDecompress() throws DataFormatException {
        inflater.reset();
        inflater.setInput(fPacked, 0, fPackedSize);
        int n = 0;
        while (n < size && !inflater.finished()) {
            int r = inflater.inflate(fOut, n, size - n);
            if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalStateException("truncated deflate stream");
            n += r;
        }
        return n;
    }

    @Benchmark
    public int lz4Compress() {
        return lz4c.compress(raw, 0, size, lPacked, 0, lPacked.length);
    }

    @Benchmark
    public int lz4Decompress() {
        return lz4d.decompress(lPacked, 0, fOut, 0, size);
    }
}
