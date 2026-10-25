package com.example.thermohammer.engine.cpu

/** JNI surface of libbenchcore.so: the CPU kernels of BenchCore/ (shared C++, also built for iOS). Ids match bench_core.h. */
internal object CpuNative {
    init {
        System.loadLibrary("benchcore")
    }

    const val MATMUL = 0
    const val FFT = 1
    const val MANDELBROT = 2
    const val SIEVE = 3
    const val RSA = 4
    const val NBODY = 5
    const val JSON = 6
    const val XML = 7
    const val ZLIB = 8
    const val AES_GCM = 9
    const val SHA256 = 10

    @JvmStatic external fun name(id: Int): String
    /** Builds the kernel's inputs; 0 for an unknown id. */
    @JvmStatic external fun create(id: Int): Long
    /** One unit of work → its checksum; throws IllegalStateException when the kernel's own check fails. */
    @JvmStatic external fun run(handle: Long): Long
    @JvmStatic external fun destroy(handle: Long)
    /** "ARMv8 crypto" or "portable" — how AES-GCM and SHA-256 run on this CPU. */
    @JvmStatic external fun cryptoPath(): String
}
