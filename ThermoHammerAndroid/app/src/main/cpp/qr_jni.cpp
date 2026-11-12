// JNI surface of the QR scanning test in libbenchcore.so — see engine/ux/QrNative.kt. The code lives in BenchCore/.
#include <jni.h>

#include <vector>

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

// The certificate's QR code: [text]'s modules (1 dark, 0 light, no quiet zone, ECC M) into the start of [out], row by
// row; returns the side, 0 when it doesn't fit [out] or can't be encoded.
QR_JNI(jint, encode)(JNIEnv* env, jclass, jstring text, jbyteArray out) {
    if (!text || !out) return 0;
    const jsize capacity = env->GetArrayLength(out);
    std::vector<uint8_t> modules(static_cast<size_t>(capacity));
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return 0;
    const int side = bc_qr_encode(chars, modules.data(), capacity);
    env->ReleaseStringUTFChars(text, chars);
    if (side > 0) env->SetByteArrayRegion(out, 0, side * side, reinterpret_cast<const jbyte*>(modules.data()));
    return side;
}
