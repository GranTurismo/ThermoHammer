package com.example.thermohammer.engine.cpu

// ─────────────────────────────────────────────────────────────────────────────
// CPU TEST KERNELS — fixed units of everyday and numeric work, in the shared
// C++ core (BenchCore/), so Android and iOS do exactly the same work.
//
//   Each kernel does the same work on every call and returns a checksum; the
//   runner checks every call against the first, so a kernel can't get faster by
//   getting wrong. All inputs are generated from fixed seeds. The kernels are
//   tested on the desktop by BenchCore/test/run.sh and on a device by
//   androidTest CpuNativeTest.
// ─────────────────────────────────────────────────────────────────────────────

/** One unit of work. Instances own their buffers — one per thread; close() frees them. */
abstract class Kernel(val name: String) {
    abstract fun run(): Long
    open fun close() {}
}

/** A BenchCore kernel ([CpuNative] ids). */
class NativeKernel(id: Int) : Kernel(CpuNative.name(id)) {
    private var handle = CpuNative.create(id).also { check(it != 0L) { "no CPU kernel $id" } }
    override fun run(): Long = CpuNative.run(handle)
    override fun close() {
        if (handle != 0L) CpuNative.destroy(handle)
        handle = 0L
    }
}
