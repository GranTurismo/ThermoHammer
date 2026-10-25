// JNI surface of libbenchcore.so — see engine/cpu/CpuNative.kt. The kernels live in BenchCore/ (shared with iOS).
#include <jni.h>

#include "bench_core.h"

#define CPU_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_cpu_CpuNative_##name

CPU_JNI(jstring, name)(JNIEnv* env, jclass, jint id) {
    const char* n = bc_kernel_name(id);
    return env->NewStringUTF(n ? n : "?");
}

CPU_JNI(jlong, create)(JNIEnv*, jclass, jint id) { return reinterpret_cast<jlong>(bc_kernel_create(id)); }

CPU_JNI(jlong, run)(JNIEnv* env, jclass, jlong handle) {
    auto* kernel = reinterpret_cast<bc_kernel*>(handle);
    int ok = 1;
    const int64_t v = bc_kernel_run(kernel, &ok);
    if (!ok) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), bc_kernel_error(kernel));
    return v;
}

CPU_JNI(void, destroy)(JNIEnv*, jclass, jlong handle) { bc_kernel_destroy(reinterpret_cast<bc_kernel*>(handle)); }

CPU_JNI(jstring, cryptoPath)(JNIEnv* env, jclass) { return env->NewStringUTF(bc_crypto_path()); }
