/* JNI entry points Celeris adds to its libzstd build, for Java 21 without
 * --enable-preview (no FFM there). They take and return plain integers only:
 * pointers travel as jlong, sizes as jlong, so no JNIEnv function is ever
 * called and the shim needs no jni.h. JNIEXPORT/JNICALL are spelled out to
 * match jni_md.h on every platform Celeris ships (x86_64 and aarch64; JNICALL
 * is __stdcall on Windows, which 64-bit compilers ignore).
 *
 * Bound by com.panzer.mods.celeris.core.memory.ZstdJniBinding. zstd errors
 * come back as the usual (size_t)-code, which Java reads as a negative long.
 */
#ifndef CELERIS_ZSTD_JNI_H
#define CELERIS_ZSTD_JNI_H

#include <stdint.h>

#if defined(_WIN32)
#  define CELERIS_JNIEXPORT __declspec(dllexport)
#  define CELERIS_JNICALL __stdcall
#else
#  define CELERIS_JNIEXPORT __attribute__((visibility("default")))
#  define CELERIS_JNICALL
#endif

#define CELERIS_JNI(name) Java_com_panzer_mods_celeris_core_memory_ZstdJniBinding_##name

#ifdef __cplusplus
extern "C" {
#endif

CELERIS_JNIEXPORT int32_t CELERIS_JNICALL JNI_OnLoad(void *vm, void *reserved);

CELERIS_JNIEXPORT int32_t CELERIS_JNICALL CELERIS_JNI(nativeVersionNumber)(void *env, void *cls);

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCreateCCtx)(void *env, void *cls);

CELERIS_JNIEXPORT void CELERIS_JNICALL CELERIS_JNI(nativeFreeCCtx)(void *env, void *cls, int64_t cctx);

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCreateDCtx)(void *env, void *cls);

CELERIS_JNIEXPORT void CELERIS_JNICALL CELERIS_JNI(nativeFreeDCtx)(void *env, void *cls, int64_t dctx);

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeCompressCCtx)(
        void *env, void *cls, int64_t cctx,
        int64_t dst, int64_t dstCapacity, int64_t src, int64_t srcSize, int32_t level);

CELERIS_JNIEXPORT int64_t CELERIS_JNICALL CELERIS_JNI(nativeDecompressDCtx)(
        void *env, void *cls, int64_t dctx,
        int64_t dst, int64_t dstCapacity, int64_t src, int64_t srcSize);

#ifdef __cplusplus
}
#endif

#endif /* CELERIS_ZSTD_JNI_H */
