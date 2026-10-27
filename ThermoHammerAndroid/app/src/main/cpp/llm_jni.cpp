// JNI surface of the LLM test in libbenchllm.so — see engine/llm/LlmNative.kt. The code lives in BenchCore/llm.
#include <jni.h>
#include <sys/auxv.h>

#include "bench_llm.h"

#define LLM_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_llm_LlmNative_##name

static bc_llm* llm(jlong h) { return reinterpret_cast<bc_llm*>(h); }

LLM_JNI(jlong, weightBytes)(JNIEnv*, jclass) { return bc_llm_weight_bytes(); }

/** The CPU has the instructions the kernels are built with (ARMv8.2 dot product and FP16): without them they'd crash. */
LLM_JNI(jboolean, cpuSupported)(JNIEnv*, jclass) {
    const unsigned long hw = getauxval(AT_HWCAP);
    constexpr unsigned long FPHP = 1UL << 9, ASIMDHP = 1UL << 10, ASIMDDP = 1UL << 20;
    return (hw & FPHP) && (hw & ASIMDHP) && (hw & ASIMDDP);
}

LLM_JNI(void, setCacheDir)(JNIEnv* env, jclass, jstring dir) {
    const char* d = env->GetStringUTFChars(dir, nullptr);
    bc_llm_set_cache_dir(d);
    env->ReleaseStringUTFChars(dir, d);
}

LLM_JNI(jstring, gpuName)(JNIEnv* env, jclass) { return env->NewStringUTF(bc_llm_gpu_name()); }

LLM_JNI(jstring, gpuApi)(JNIEnv* env, jclass) { return env->NewStringUTF(bc_llm_gpu_api()); }

LLM_JNI(jdouble, agreement)(JNIEnv*, jclass, jlong h) { return bc_llm_agreement(reinterpret_cast<bc_llm*>(h)); }

/** The model handle, or 0 with the reason in error[0]. */
LLM_JNI(jlong, create)(JNIEnv* env, jclass, jboolean gpu, jint threads, jlong seed, jobjectArray error) {
    char err[512] = "";
    bc_llm* h = bc_llm_create(gpu ? 1 : 0, threads, static_cast<uint64_t>(seed), err, sizeof err);
    if (!h && error && env->GetArrayLength(error) > 0) env->SetObjectArrayElement(error, 0, env->NewStringUTF(err));
    return reinterpret_cast<jlong>(h);
}

/** [decode threads, prefill threads], or null when a trial run failed. */
LLM_JNI(jintArray, tuneThreads)(JNIEnv* env, jclass, jlong h) {
    int t = 0, b = 0;
    if (!bc_llm_tune_threads(llm(h), &t, &b)) return nullptr;
    jint out[2] = {t, b};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, out);
    return a;
}

LLM_JNI(void, setThreads)(JNIEnv*, jclass, jlong h, jint threads, jint batchThreads) {
    bc_llm_set_threads(llm(h), threads, batchThreads);
}

LLM_JNI(jdouble, prefill)(JNIEnv*, jclass, jlong h, jint tokens) { return bc_llm_prefill(llm(h), tokens); }

LLM_JNI(void, reset)(JNIEnv*, jclass, jlong h) { bc_llm_reset(llm(h)); }

/** Seconds for one token, its id in token[0]; < 0 on failure. */
LLM_JNI(jdouble, next)(JNIEnv* env, jclass, jlong h, jintArray token) {
    int t = 0;
    const double s = bc_llm_next(llm(h), &t);
    if (s >= 0 && token) {
        const jint v = t;
        env->SetIntArrayRegion(token, 0, 1, &v);
    }
    return s;
}

LLM_JNI(void, cancel)(JNIEnv*, jclass, jlong h) { bc_llm_cancel(llm(h)); }

LLM_JNI(jstring, error)(JNIEnv* env, jclass, jlong h) { return env->NewStringUTF(bc_llm_error(llm(h))); }

LLM_JNI(void, destroy)(JNIEnv*, jclass, jlong h) { bc_llm_destroy(llm(h)); }

LLM_JNI(jint, largestModel)(JNIEnv*, jclass, jdouble bytes) { return bc_llm_largest_model(bytes); }
