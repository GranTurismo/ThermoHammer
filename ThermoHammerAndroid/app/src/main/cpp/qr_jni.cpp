// JNI surface of the QR scanning test in libbenchcore.so — see engine/ux/QrNative.kt. The code lives in BenchCore/.
#include <jni.h>

#include "bench_core.h"

#define QR_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_ux_QrNative_##name

static bc_qr* qr(jlong h) { return reinterpret_cast<bc_qr*>(h); }

QR_JNI(jlong, create)(JNIEnv*, jclass, jint count, jint seed) { return reinterpret_cast<jlong>(bc_qr_create(count, seed)); }

QR_JNI(jint, count)(JNIEnv*, jclass, jlong h) { return bc_qr_count(qr(h)); }

QR_JNI(void, luma)(JNIEnv* env, jclass, jlong h, jint index, jbyteArray out) {
    env->SetByteArrayRegion(out, 0, BC_QR_W * BC_QR_H, reinterpret_cast<const jbyte*>(bc_qr_luma(qr(h), index)));
}

QR_JNI(jstring, text)(JNIEnv* env, jclass, jlong h, jint index) { return env->NewStringUTF(bc_qr_text(qr(h), index)); }

QR_JNI(void, corners)(JNIEnv* env, jclass, jlong h, jint index, jfloatArray out) {
    float c[8];
    bc_qr_corners(qr(h), index, c);
    env->SetFloatArrayRegion(out, 0, 8, c);
}

QR_JNI(jboolean, scan)(JNIEnv* env, jclass, jlong h, jint index, jfloatArray found) {
    float c[8];
    const int ok = bc_qr_scan(qr(h), index, found ? c : nullptr);
    if (ok && found) env->SetFloatArrayRegion(found, 0, 8, c);
    return ok != 0;
}

QR_JNI(void, destroy)(JNIEnv*, jclass, jlong h) { bc_qr_destroy(qr(h)); }
