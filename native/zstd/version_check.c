/* Built and run by CTest in CI: the freshly built library loads, reports the
 * pinned release, round-trips a buffer, and its JNI entry points (called here
 * directly, with no JVM: they never touch JNIEnv) do the same through reused
 * contexts. */
#include <stdio.h>
#include <string.h>
#include "zstd.h"
#include "celeris_zstd_jni.h"

static int jni_round_trips(const char *src, size_t n) {
    char packed[8192], out[4096];
    int64_t cctx = CELERIS_JNI(nativeCreateCCtx)(NULL, NULL);
    int64_t dctx = CELERIS_JNI(nativeCreateDCtx)(NULL, NULL);
    if (cctx == 0 || dctx == 0) return 4;
    /* Twice per context: the second pass proves a context is reusable. */
    for (int pass = 0; pass < 2; pass++) {
        int64_t c = CELERIS_JNI(nativeCompressCCtx)(NULL, NULL, cctx,
                (int64_t) (intptr_t) packed, (int64_t) sizeof packed,
                (int64_t) (intptr_t) src, (int64_t) n, 3);
        if (ZSTD_isError((size_t) c)) return 5;
        memset(out, 0, sizeof out);
        int64_t d = CELERIS_JNI(nativeDecompressDCtx)(NULL, NULL, dctx,
                (int64_t) (intptr_t) out, (int64_t) sizeof out,
                (int64_t) (intptr_t) packed, c);
        if (ZSTD_isError((size_t) d) || (size_t) d != n || memcmp(src, out, n) != 0) return 6;
    }
    /* Errors cross as (size_t)-code, i.e. a negative jlong. */
    int64_t bad = CELERIS_JNI(nativeDecompressDCtx)(NULL, NULL, dctx,
            (int64_t) (intptr_t) out, (int64_t) sizeof out, (int64_t) (intptr_t) src, (int64_t) 16);
    if (bad >= 0 || !ZSTD_isError((size_t) bad)) return 7;
    CELERIS_JNI(nativeFreeCCtx)(NULL, NULL, cctx);
    CELERIS_JNI(nativeFreeDCtx)(NULL, NULL, dctx);
    if ((unsigned) CELERIS_JNI(nativeVersionNumber)(NULL, NULL) != ZSTD_versionNumber()) return 8;
    return 0;
}

int main(int argc, char **argv) {
    const char *expected = argc > 1 ? argv[1] : ZSTD_VERSION_STRING;
    if (strcmp(ZSTD_versionString(), expected) != 0) {
        fprintf(stderr, "libzstd %s, expected %s\n", ZSTD_versionString(), expected);
        return 1;
    }
    char src[4096], packed[8192], out[4096];
    for (int i = 0; i < (int) sizeof src; i++) src[i] = (char) (i * 7 % 61);
    size_t n = ZSTD_compress(packed, sizeof packed, src, sizeof src, 3);
    if (ZSTD_isError(n)) return 2;
    size_t m = ZSTD_decompress(out, sizeof out, packed, n);
    if (ZSTD_isError(m) || m != sizeof src || memcmp(src, out, m) != 0) return 3;
    int jni = jni_round_trips(src, sizeof src);
    if (jni != 0) {
        fprintf(stderr, "JNI entry points failed (step %d)\n", jni);
        return jni;
    }
    printf("libzstd %s ok (%zu -> %zu bytes, JNI entry points ok)\n", ZSTD_versionString(), sizeof src, n);
    return 0;
}
