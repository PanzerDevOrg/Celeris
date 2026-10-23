// JNI entry points of celeris_physics, for Java 21 where FFM is preview-only
// (or blocked by --enable-native-access). They forward to the C ABI in
// celeris_physics.h and take only integers, doubles and addresses carried as
// jlong, so no JNIEnv function is called and no jni.h (nor JDK) is needed to
// build them. JNIEXPORT/JNICALL are spelled out as in native/zstd/celeris_zstd_jni.h.
//
// Bound by com.panzer.mods.celeris.physics.JniPhysicsKernel.
#include <stdint.h>

#include "celeris_physics.h"

#if defined(_WIN32)
#  define CP_JNICALL __stdcall
#else
#  define CP_JNICALL
#endif

#define CP_JNI(name) Java_com_panzer_mods_celeris_physics_JniPhysicsKernel_##name

#define AS_PTR(type, v) ((type) (intptr_t) (v))

extern "C" {

CP_EXPORT int32_t CP_JNICALL JNI_OnLoad(void*, void*) {
    return 0x00010008; /* JNI_VERSION_1_8 */
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeAbiVersion)(void*, void*) {
    return cp_abi_version();
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeLayoutSignature)(void*, void*) {
    return cp_layout_signature();
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeActiveIsa)(void*, void*) {
    return cp_active_isa();
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeForceIsa)(void*, void*, int32_t isa) {
    return cp_force_isa(isa);
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeStep)(void*, void*, int64_t bodies, int32_t capacity, int64_t terrain,
                                                int32_t begin, int32_t end, int32_t mode) {
    return cp_step(AS_PTR(void*, bodies), capacity, AS_PTR(const cp_terrain*, terrain), begin, end, mode);
}

CP_EXPORT int32_t CP_JNICALL CP_JNI(nativeBroadphase)(void*, void*, int64_t bodies, int32_t capacity, int32_t count,
                                                      int64_t buckets, int32_t bucketsLen, double margin,
                                                      int64_t pairs, int32_t pairCapacity, int32_t flags) {
    return cp_broadphase(AS_PTR(void*, bodies), capacity, count, AS_PTR(int32_t*, buckets), bucketsLen, margin,
                         AS_PTR(int32_t*, pairs), pairCapacity, flags);
}

}  // extern "C"
