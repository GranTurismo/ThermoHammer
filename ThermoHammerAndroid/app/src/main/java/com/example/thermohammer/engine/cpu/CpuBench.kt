package com.example.thermohammer.engine.cpu

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln

/**
 * One CPU test of the benchmark: which kernels ([CpuNative] ids, `labels` their names in order), and whether they
 * run on one core or all of them.
 */
enum class CpuTest(val number: Int, val title: String, val seconds: Int, val labels: List<String>,
                   val kernelIds: List<Int>, val multiCore: Boolean = false) {
    MATH(1, "MATH", 10, listOf("MATRIX MULTIPLY", "FFT", "MANDELBROT", "PRIME SIEVE", "RSA 1024", "N-BODY"),
        listOf(CpuNative.MATMUL, CpuNative.FFT, CpuNative.MANDELBROT, CpuNative.SIEVE, CpuNative.RSA, CpuNative.NBODY)),
    DATA(2, "JSON & XML", 9, listOf("JSON", "XML"), listOf(CpuNative.JSON, CpuNative.XML)),
    CRYPTO(3, "COMPRESSION & CRYPTO", 9, listOf("ZLIB", "AES-256-GCM", "SHA-256"),
        listOf(CpuNative.ZLIB, CpuNative.AES_GCM, CpuNative.SHA256)),
    MULTI(4, "MULTI-CORE", 10, listOf("MATRIX MULTIPLY", "FFT", "MANDELBROT", "JSON", "ZLIB", "SHA-256"),
        listOf(CpuNative.MATMUL, CpuNative.FFT, CpuNative.MANDELBROT, CpuNative.JSON, CpuNative.ZLIB, CpuNative.SHA256),
        multiCore = true);

    /** Makers of the test's kernels, in order. */
    val kernels: List<() -> Kernel> get() = kernelIds.map { id -> { NativeKernel(id) } }

    /** Timed seconds per kernel; tests take ~8 s each. */
    val secondsPerKernel: Double get() = 8.0 / kernelIds.size
}

/** Live state for the stage. */
data class CpuProgress(val kernel: String, val fraction: Float, val rate: Double, val threads: Int)

/** Kernel throughputs of one test (operations per second, all threads together for MULTI) and their geometric mean. */
data class CpuTestRun(val test: CpuTest, val rates: Map<String, Double>) {
    val value: Double get() = if (rates.isEmpty() || rates.values.any { it <= 0 }) 0.0
        else exp(rates.values.sumOf { ln(it) } / rates.size)
}

/**
 * Runs CPU tests: each kernel is warmed up untimed (caches filled, clocks up), then timed for
 * the test's share of seconds. Every call's checksum must equal the first one's.
 */
class CpuBench(
    private val threads: Int = Runtime.getRuntime().availableProcessors(),
    private val kernelsOf: (CpuTest) -> List<() -> Kernel> = { it.kernels }   // tests swap in plain-Kotlin kernels
) {

    companion object {
        private const val WARMUP_NS = 300_000_000L
    }

    fun run(test: CpuTest, isActive: () -> Boolean, onProgress: (CpuProgress) -> Unit): CpuTestRun {
        val rates = LinkedHashMap<String, Double>()
        val kernels = kernelsOf(test)
        for ((k, make) in kernels.withIndex()) {
            if (!isActive()) break
            val name = test.labels.getOrElse(k) { "K$k" }
            val rate = if (test.multiCore) multi(make, test.secondsPerKernel, isActive) { done, r ->
                onProgress(CpuProgress(name, (k + done) / kernels.size, r, threads))
            } else make().let { kernel ->
                try {
                    single(kernel, test.secondsPerKernel, isActive) { done, r ->
                        onProgress(CpuProgress(name, (k + done) / kernels.size, r, 1))
                    }
                } finally {
                    kernel.close()
                }
            }
            rates[name] = rate
        }
        return CpuTestRun(test, rates)
    }

    private fun single(kernel: Kernel, seconds: Double, isActive: () -> Boolean, report: (Float, Double) -> Unit): Double {
        val expected = kernel.run()
        var t = System.nanoTime()
        while (System.nanoTime() - t < WARMUP_NS) check(kernel.run() == expected) { "${kernel.name} result changed" }
        val limit = (seconds * 1e9).toLong()
        var n = 0L
        t = System.nanoTime()
        var lastReport = t
        while (System.nanoTime() - t < limit && isActive()) {
            check(kernel.run() == expected) { "${kernel.name} result changed" }
            n++
            val now = System.nanoTime()
            if (now - lastReport > 150_000_000L) { lastReport = now; report((now - t).toFloat() / limit, n * 1e9 / (now - t)) }
        }
        return n * 1e9 / (System.nanoTime() - t)
    }

    /** Every core runs its own instance at once; the rate is all of them together. */
    private fun multi(make: () -> Kernel, seconds: Double, isActive: () -> Boolean, report: (Float, Double) -> Unit): Double {
        val ops = AtomicLong(0)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val stopAt = AtomicLong(Long.MAX_VALUE)
        val error = arrayOfNulls<Throwable>(1)
        val workers = (0 until threads).map {
            Thread {
                var kernel: Kernel? = null
                try {
                    kernel = make()
                    val expected = kernel.run()
                    val w = System.nanoTime()
                    while (System.nanoTime() - w < WARMUP_NS) kernel.run()
                    ready.countDown()
                    go.await()
                    while (System.nanoTime() < stopAt.get()) {
                        check(kernel.run() == expected) { "${kernel.name} result changed" }
                        ops.incrementAndGet()
                    }
                    kernel.close()
                } catch (t: Throwable) {
                    kernel?.close()
                    synchronized(error) { if (error[0] == null) error[0] = t }
                    ready.countDown()
                }
            }.also { it.name = "th-cpu-$it"; it.start() }
        }
        ready.await()
        val limit = (seconds * 1e9).toLong()
        val t0 = System.nanoTime()
        stopAt.set(t0 + limit)
        go.countDown()
        while (System.nanoTime() - t0 < limit && isActive()) {
            Thread.sleep(150)
            val el = System.nanoTime() - t0
            report((el.toFloat() / limit).coerceAtMost(1f), ops.get() * 1e9 / el)
        }
        stopAt.set(0)
        workers.forEach { it.join() }
        error[0]?.let { throw it }
        return ops.get() * 1e9 / (System.nanoTime() - t0)
    }
}
