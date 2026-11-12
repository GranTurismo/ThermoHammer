// Which cores a thread may run on — the AI CPU test's way of trying "the threads LiteRT starts stay on the fast cores"
// (engine/CpuAffinity.kt). A thread's mask is inherited by the threads it starts, so pinning the calling thread
// just before LiteRT builds its CPU pool places the pool's workers; the caller puts the mask back afterwards.
// Nothing here runs a kernel or changes anything unless asked, and a refusal (an OEM kernel, a core that is off) is
// only a false return. No C++ library is used (the library stays a few kilobytes).
#include <jni.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>

#define AFFINITY_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_example_thermohammer_engine_CpuAffinityNative_##name

/** The calling thread's kernel id (what /proc/self/task/<tid> and the other calls here take). */
AFFINITY_JNI(jint, tid)(JNIEnv*, jclass) { return static_cast<jint>(syscall(__NR_gettid)); }

/** The cores thread `tid` (0: the caller) may run on, ascending; empty when the kernel won't say. */
AFFINITY_JNI(jintArray, get)(JNIEnv* env, jclass, jint tid) {
    cpu_set_t set;
    CPU_ZERO(&set);
    jint cores[CPU_SETSIZE];
    jsize n = 0;
    if (sched_getaffinity(static_cast<pid_t>(tid), sizeof set, &set) == 0)
        for (int i = 0; i < CPU_SETSIZE; i++) if (CPU_ISSET(i, &set)) cores[n++] = i;
    jintArray a = env->NewIntArray(n);
    if (a && n > 0) env->SetIntArrayRegion(a, 0, n, cores);
    return a;
}

/** Lets thread `tid` (0: the caller) run only on `cores`; false when the kernel refused or the list was empty. */
AFFINITY_JNI(jboolean, set)(JNIEnv* env, jclass, jint tid, jintArray cores) {
    const jsize n = cores ? env->GetArrayLength(cores) : 0;
    if (n <= 0 || n > CPU_SETSIZE) return JNI_FALSE;
    jint list[CPU_SETSIZE];
    env->GetIntArrayRegion(cores, 0, n, list);
    cpu_set_t set;
    CPU_ZERO(&set);
    int used = 0;
    for (jsize i = 0; i < n; i++) if (list[i] >= 0 && list[i] < CPU_SETSIZE) { CPU_SET(list[i], &set); used++; }
    if (used == 0) return JNI_FALSE;
    return sched_setaffinity(static_cast<pid_t>(tid), sizeof set, &set) == 0 ? JNI_TRUE : JNI_FALSE;
}
