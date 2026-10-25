// JNI surface of the MEM tests in libbenchcore.so — see engine/mem/MemNative.kt. The code lives in BenchCore/.
#include <jni.h>

#include "bench_core.h"

#define MEM_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_mem_MemNative_##name

MEM_JNI(jstring, lastError)(JNIEnv* env, jclass) { return env->NewStringUTF(bc_mem_error()); }

MEM_JNI(jlong, ramOpen)(JNIEnv*, jclass, jlong bytes, jint threads) { return reinterpret_cast<jlong>(bc_ram_open(bytes, threads)); }

MEM_JNI(jdouble, ramRun)(JNIEnv*, jclass, jlong h, jint mode, jdouble secs) {
    return bc_ram_run(reinterpret_cast<bc_ram*>(h), mode, secs);
}

MEM_JNI(void, ramClose)(JNIEnv*, jclass, jlong h) { bc_ram_close(reinterpret_cast<bc_ram*>(h)); }

MEM_JNI(jlong, diskOpen)(JNIEnv* env, jclass, jstring jpath, jlong bytes) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bc_disk* d = bc_disk_open(path, bytes);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(d);
}

MEM_JNI(jboolean, diskDirect)(JNIEnv*, jclass, jlong h) { return bc_disk_direct(reinterpret_cast<bc_disk*>(h)) != 0; }

MEM_JNI(jint, diskPasses)(JNIEnv*, jclass, jlong h) { return bc_disk_passes(reinterpret_cast<bc_disk*>(h)); }

MEM_JNI(jdouble, diskRun)(JNIEnv*, jclass, jlong h, jint mode, jint threads, jdouble secs) {
    return bc_disk_run(reinterpret_cast<bc_disk*>(h), mode, threads, secs);
}

MEM_JNI(void, diskClose)(JNIEnv*, jclass, jlong h) { bc_disk_close(reinterpret_cast<bc_disk*>(h)); }
