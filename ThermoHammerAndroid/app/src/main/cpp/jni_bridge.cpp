// JNI surface of libneonrt.so — see engine/gpu/NeonRtNative.kt.
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "neon_rt.h"

namespace {

thread_local std::string lastError;

nrt::NeonRt* get(jlong h) { return reinterpret_cast<nrt::NeonRt*>(h); }

template <typename T>
T* address(JNIEnv* env, jobject buffer) {
    return buffer ? static_cast<T*>(env->GetDirectBufferAddress(buffer)) : nullptr;
}

}  // namespace

#define NRT_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_gpu_NeonRtNative_##name

NRT_JNI(jstring, probe)(JNIEnv* env, jclass) {
    std::string why;
    try {
        why = nrt::probeRayQuery();
    } catch (const std::exception& e) {
        why = e.what();
    }
    return why.empty() ? nullptr : env->NewStringUTF(why.c_str());
}

NRT_JNI(jlong, create)(JNIEnv* env, jclass, jobject asset, jint size, jobjectArray actorMeshes,
                      jintArray actorCapacity, jint width, jint height, jboolean rayTracing) {
    try {
        auto* bytes = address<uint8_t>(env, asset);
        if (!bytes) throw std::runtime_error("asset buffer is not direct");
        std::vector<std::string> names;
        for (jsize i = 0; i < env->GetArrayLength(actorMeshes); i++) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(actorMeshes, i));
            const char* c = env->GetStringUTFChars(js, nullptr);
            names.emplace_back(c);
            env->ReleaseStringUTFChars(js, c);
            env->DeleteLocalRef(js);
        }
        std::vector<int> caps(env->GetArrayLength(actorCapacity));
        env->GetIntArrayRegion(actorCapacity, 0, (jsize)caps.size(), caps.data());
        auto* r = new nrt::NeonRt(bytes, (size_t)size, names, caps, width, height, rayTracing);
        return reinterpret_cast<jlong>(r);
    } catch (const std::exception& e) {
        lastError = e.what();
        return 0;
    }
}

NRT_JNI(jstring, lastError)(JNIEnv* env, jclass) { return env->NewStringUTF(lastError.c_str()); }

NRT_JNI(jstring, deviceName)(JNIEnv* env, jclass, jlong h) { return env->NewStringUTF(get(h)->deviceName.c_str()); }

NRT_JNI(jlong, triangles)(JNIEnv*, jclass, jlong h) { return get(h)->triangles; }

NRT_JNI(jboolean, render)(JNIEnv* env, jclass, jlong h, jobject frame, jobject lights, jint lightCount, jobject cells,
                         jobject indices, jint indexCount, jobject actors, jintArray actorCounts, jboolean readback) {
    try {
        std::vector<int32_t> counts(env->GetArrayLength(actorCounts));
        env->GetIntArrayRegion(actorCounts, 0, (jsize)counts.size(), counts.data());
        nrt::FrameInput in{};
        in.frame = address<float>(env, frame);
        in.lights = address<float>(env, lights);
        in.lightCount = lightCount;
        in.cells = address<int32_t>(env, cells);
        in.indices = address<uint16_t>(env, indices);
        in.indexCount = indexCount;
        in.actors = address<float>(env, actors);
        in.actorCounts = counts.data();
        in.readback = readback;
        if (!in.frame || !in.lights || !in.cells || !in.indices || !in.actors) throw std::runtime_error("frame buffers must be direct");
        get(h)->render(in);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        lastError = e.what();
        return JNI_FALSE;
    }
}

NRT_JNI(void, setWindow)(JNIEnv* env, jclass, jlong h, jobject surface) {
    try {
        get(h)->setWindow(surface ? ANativeWindow_fromSurface(env, surface) : nullptr);
    } catch (const std::exception& e) {
        lastError = e.what();
    }
}

NRT_JNI(void, present)(JNIEnv*, jclass, jlong h) {
    try {
        get(h)->present();
    } catch (const std::exception& e) {
        lastError = e.what();
    }
}

NRT_JNI(void, readPixels)(JNIEnv* env, jclass, jlong h, jobject out) {
    auto* dst = address<uint8_t>(env, out);
    if (dst && get(h)->pixels()) memcpy(dst, get(h)->pixels(), (size_t)env->GetDirectBufferCapacity(out));
}

NRT_JNI(void, destroy)(JNIEnv*, jclass, jlong h) { delete get(h); }
