/* JNI shim compiled into Celeris's libzstd (see celeris_zstd_jni.h). Each
 * function forwards straight to the zstd call of the same shape: contexts are
 * created once and reused by Java's NativeContextPool, so no call here
 * allocates except ZSTD_create*Ctx itself. */
#include <stddef.h>
#include "zstd.h"
#include "celeris_zstd_jni.h"

#define AS_PTR(type, v) ((type) (intptr_t) (v))

CELERIS_JNIEXPORT int32_t CELERIS_JNICALL JNI_OnLoad(void *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return 0x00010008; /* JNI_VERSION_1_8 */
}

CELERIS_JNIEXPORT int32_t CELERIS_JNICALL CELERIS_JNI(nativeVersionNumber)(void *env, void *cls) {
    (void) env;
    (void) cls;
    return (int32_t) ZSTD_versionNumber();
}

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCreateCCtx)(void *env, void *cls) {
    (void) env;
    (void) cls;
    return (int64_t) (intptr_t) ZSTD_createCCtx();
}

CELERIS_JNIEXPORT void CELERIS_JNICALL CELERIS_JNI(nativeFreeCCtx)(void *env, void *cls, int64_t cctx) {
    (void) env;
    (void) cls;
    ZSTD_freeCCtx(AS_PTR(ZSTD_CCtx *, cctx));
}

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCreateDCtx)(void *env, void *cls) {
    (void) env;
    (void) cls;
    return (int64_t) (intptr_t) ZSTD_createDCtx();
}

CELERIS_JNIEXPORT void CELERIS_JNICALL CELERIS_JNI(nativeFreeDCtx)(void *env, void *cls, int64_t dctx) {
    (void) env;
    (void) cls;
    ZSTD_freeDCtx(AS_PTR(ZSTD_DCtx *, dctx));
}

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCompressCCtx)(
        void *env, void *cls, int64_t cctx,
        int64_t dst, int64_t dstCapacity, int64_t src, int64_t srcSize, int32_t level) {
    (void) env;
    (void) cls;
    return (int64_t) ZSTD_compressCCtx(AS_PTR(ZSTD_CCtx *, cctx),
            AS_PTR(void *, dst), (size_t) dstCapacity,
            AS_PTR(const void *, src), (size_t) srcSize, level);
}

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeDecompressDCtx)(
        void *env, void *cls, int64_t dctx,
        int64_t dst, int64_t dstCapacity, int64_t src, int64_t srcSize) {
    (void) env;
    (void) cls;
    return (int64_t) ZSTD_decompressDCtx(AS_PTR(ZSTD_DCtx *, dctx),
            AS_PTR(void *, dst), (size_t) dstCapacity,
            AS_PTR(const void *, src), (size_t) srcSize);
}
