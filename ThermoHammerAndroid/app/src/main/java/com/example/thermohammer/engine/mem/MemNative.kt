package com.example.thermohammer.engine.mem

/** JNI surface of the MEM tests in libbenchcore.so (BenchCore/src/mem.cpp, shared with iOS): measured in slices so the stage stays live. */
internal object MemNative {
    init {
        System.loadLibrary("benchcore")
    }

    const val READ = 0
    const val WRITE = 1
    const val COPY = 2
    const val LATENCY = 3

    const val SEQ_WRITE = 0
    const val SEQ_READ = 1
    const val RANDOM_READ = 2
    const val RANDOM_WRITE = 3

    @JvmStatic external fun lastError(): String

    /** A buffer of [bytes] with every page already touched; 0 if it can't be allocated. */
    @JvmStatic external fun ramOpen(bytes: Long, threads: Int): Long
    /** Bytes per second (READ, WRITE, COPY — all threads together) or nanoseconds per random access (LATENCY). */
    @JvmStatic external fun ramRun(handle: Long, mode: Int, seconds: Double): Double
    @JvmStatic external fun ramClose(handle: Long)

    /** Creates the test file (direct I/O where the file system allows it); 0 on failure. */
    @JvmStatic external fun diskOpen(path: String, bytes: Long): Long
    /** Reads really bypass the page cache — checked when reading starts (O_DIRECT can be accepted and not honoured). */
    @JvmStatic external fun diskDirect(handle: Long): Boolean
    /** Whole passes over the file the sequential write has completed. */
    @JvmStatic external fun diskPasses(handle: Long): Int
    /** Bytes per second (sequential) or 4 KB operations per second (random); negative on an I/O error. */
    @JvmStatic external fun diskRun(handle: Long, mode: Int, threads: Int, seconds: Double): Double
    /** Closes and deletes the test file. */
    @JvmStatic external fun diskClose(handle: Long)
}
