// JNI surface of the LLM test in libbenchllm.so — see engine/llm/LlmNative.kt. The code lives in BenchCore/llm.
// Built twice: into libbenchllm.so (class LlmNative) and, with -DLLM_JNI_CLASS=LlmNativeI8mm, into the CPU-only
// libbenchllm_i8mm.so (engine/llm/LlmNativeI8mm.kt). A JNI function is found by its class's name, so the two libraries
// answer to different classes and never to each other's calls.
#include <jni.h>
#include <sys/auxv.h>

#include <string>
#include <vector>

#include "bench_llm.h"

#ifndef LLM_JNI_CLASS
#define LLM_JNI_CLASS LlmNative
#endif
// two steps, because an argument next to ## is pasted unexpanded: the middle macro expands LLM_JNI_CLASS first
#define LLM_JNI_JOIN(cls, ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_llm_##cls##_##name
#define LLM_JNI_NAMED(cls, ret, name) LLM_JNI_JOIN(cls, ret, name)
#define LLM_JNI(ret, name) LLM_JNI_NAMED(LLM_JNI_CLASS, ret, name)

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

/** The logits sample of the last prefill (BenchCore/llm): what the two CPU libraries' answers are compared by. */
LLM_JNI(jfloatArray, lastSample)(JNIEnv* env, jclass, jlong h) {
    const int n = bc_llm_last_sample(llm(h), nullptr, 0);
    jfloatArray a = env->NewFloatArray(n);
    if (a && n > 0) {
        std::vector<float> v(static_cast<size_t>(n), 0.0f);
        bc_llm_last_sample(llm(h), v.data(), n);
        env->SetFloatArrayRegion(a, 0, n, v.data());
    }
    return a;
}

/** The model handle, or 0 with the reason in error[0]. */
LLM_JNI(jlong, create)(JNIEnv* env, jclass, jboolean gpu, jint threads, jlong seed, jobjectArray error) {
    char err[512] = "";
    bc_llm* h = bc_llm_create(gpu ? 1 : 0, threads, static_cast<uint64_t>(seed), err, sizeof err);
    if (!h && error && env->GetArrayLength(error) > 0) env->SetObjectArrayElement(error, 0, env->NewStringUTF(err));
    return reinterpret_cast<jlong>(h);
}

/** The few-millisecond kernel self-test (BenchCore/llm): this library's logits from a tiny seeded model, or null with the reason in error[0]. */
LLM_JNI(jfloatArray, kernelSelftest)(JNIEnv* env, jclass, jint threads, jobjectArray error) {
    std::vector<float> logits(static_cast<size_t>(BC_LLM_SELFTEST_LOGITS), 0.0f);
    char err[256] = "";
    const int n = bc_llm_kernel_selftest(threads, logits.data(), static_cast<int>(logits.size()), err, sizeof err);
    if (n <= 0) {
        if (error && env->GetArrayLength(error) > 0) env->SetObjectArrayElement(error, 0, env->NewStringUTF(err));
        return nullptr;
    }
    jfloatArray a = env->NewFloatArray(n);
    if (a) env->SetFloatArrayRegion(a, 0, n, logits.data());
    return a;
}

/** What the tuning callback needs to reach LlmNative.TuneListener.onTrial; it runs on the thread that called tuneThreads. */
struct TuneCall {
    JNIEnv* env;
    jobject listener;
    jmethodID onTrial;
};

static void on_trial(void* user, int phase, int threads, int trial, int cores) {
    auto* c = static_cast<TuneCall*>(user);
    c->env->CallVoidMethod(c->listener, c->onTrial, phase, threads, trial, cores);
    if (c->env->ExceptionCheck()) c->env->ExceptionClear();   // a progress message is never worth failing the test
}

/** [decode threads, prefill threads], or null when a trial run failed. `listener` hears each trial before it runs. */
LLM_JNI(jintArray, tuneThreads)(JNIEnv* env, jclass, jlong h, jobject listener) {
    int t = 0, b = 0;
    TuneCall call{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        call.onTrial = env->GetMethodID(cls, "onTrial", "(IIII)V");
        env->DeleteLocalRef(cls);
        if (!call.onTrial) env->ExceptionClear();
    }
    const bool report = call.onTrial != nullptr;
    if (!bc_llm_tune_threads_progress(llm(h), &t, &b, report ? on_trial : nullptr, report ? &call : nullptr)) return nullptr;
    jint out[2] = {t, b};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, out);
    return a;
}

LLM_JNI(void, setThreads)(JNIEnv*, jclass, jlong h, jint threads, jint batchThreads) {
    bc_llm_set_threads(llm(h), threads, batchThreads);
}

/** The cores the CPU threads may use, fastest first, and which pools are pinned to them; returns how many were usable. */
LLM_JNI(jint, setCores)(JNIEnv* env, jclass, jlong h, jintArray cores, jboolean pinDecode, jboolean pinPrompt) {
    const jsize n = cores ? env->GetArrayLength(cores) : 0;
    std::vector<jint> list(static_cast<size_t>(n), 0);
    if (n > 0) env->GetIntArrayRegion(cores, 0, n, list.data());
    return bc_llm_set_cores(llm(h), list.data(), n, pinDecode ? 1 : 0, pinPrompt ? 1 : 0);
}

/** What the tuning callback of the pinned search needs to reach LlmPinTuneListener.onTrial. */
static void on_pinned_trial(void* user, int phase, int pinned, int threads, int trial, int cores) {
    auto* c = static_cast<TuneCall*>(user);
    c->env->CallVoidMethod(c->listener, c->onTrial, phase, pinned, threads, trial, cores);
    if (c->env->ExceptionCheck()) c->env->ExceptionClear();
}

/**
 * The thread search over `cores` (bc_llm_tune_threads_pinned) as numbers, or null when a trial failed:
 * [pinnable, decodePinned, promptPinned, decodeSkipped, promptSkipped, decodeThreads, promptThreads, mainCore,
 * seconds, trials, then per trial: phase, pinned, threads, tokens, seconds] — decoded by LlmTuneReport.
 */
LLM_JNI(jdoubleArray, tuneThreadsPinned)(JNIEnv* env, jclass, jlong h, jintArray cores, jobject listener) {
    const jsize n = cores ? env->GetArrayLength(cores) : 0;
    std::vector<jint> list(static_cast<size_t>(n), 0);
    if (n > 0) env->GetIntArrayRegion(cores, 0, n, list.data());
    TuneCall call{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        call.onTrial = env->GetMethodID(cls, "onTrial", "(IIIII)V");
        env->DeleteLocalRef(cls);
        if (!call.onTrial) env->ExceptionClear();
    }
    const bool report = call.onTrial != nullptr;
    int t = 0, b = 0;
    bc_llm_pin_report r;
    if (!bc_llm_tune_threads_pinned(llm(h), list.data(), n, &t, &b, &r, report ? on_pinned_trial : nullptr, report ? &call : nullptr))
        return nullptr;
    std::vector<jdouble> out = {double(r.pinnable), double(r.decode_pinned), double(r.prompt_pinned), double(r.decode_skipped),
                                double(r.prompt_skipped), double(r.decode_threads), double(r.prompt_threads), double(r.main_core),
                                r.seconds, double(r.n_trials)};
    for (int i = 0; i < r.n_trials; i++) {
        const bc_llm_trial& x = r.trials[i];
        out.insert(out.end(), {double(x.phase), double(x.pinned), double(x.threads), double(x.tokens), x.seconds});
    }
    jdoubleArray a = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (a) env->SetDoubleArrayRegion(a, 0, static_cast<jsize>(out.size()), out.data());
    return a;
}

/** The logit sample a CPU run of `seed` gave for a prompt of `tokens`, from the other library (see bc_llm_set_reference). */
LLM_JNI(void, setReference)(JNIEnv* env, jclass, jlong seed, jint tokens, jfloatArray sample) {
    const jsize n = sample ? env->GetArrayLength(sample) : 0;
    std::vector<jfloat> v(static_cast<size_t>(n), 0.0f);
    if (n > 0) env->GetFloatArrayRegion(sample, 0, n, v.data());
    bc_llm_set_reference(static_cast<uint64_t>(seed), tokens, v.data(), n);
}

/**
 * What the GPU backends said about the device they chose, as lines (nothing in the CPU-only library). Driver strings are
 * the phone's, and NewStringUTF aborts the app on bytes that aren't valid UTF-8: only printable ASCII is passed on.
 */
LLM_JNI(jstring, backendLog)(JNIEnv* env, jclass) {
    std::string text = bc_llm_backend_log();
    for (char& c : text) if (c != '\n' && (static_cast<unsigned char>(c) < 0x20 || static_cast<unsigned char>(c) > 0x7e)) c = '?';
    return env->NewStringUTF(text.c_str());
}

/** The GPU backend to run on instead of the usual choice ("Vulkan"); "" restores the choice. */
LLM_JNI(void, setGpuApi)(JNIEnv* env, jclass, jstring api) {
    const char* a = api ? env->GetStringUTFChars(api, nullptr) : nullptr;
    bc_llm_set_gpu_api(a ? a : "");
    if (a) env->ReleaseStringUTFChars(api, a);
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
