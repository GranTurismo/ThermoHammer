package com.example.thermohammer.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.engine.cpu.NativeKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures a [SpeedMeasurement]: the benchmark's multi-core CPU work (CPU test 4's six BenchCore kernels) on every core
 * for 30 s, counted per 1-s window. Each thread runs the six kernels in turn; a work unit is one round of all six, so
 * the rate doesn't depend on which kernel happens to be running. Every call's checksum must equal its first one's.
 * Cancelling the coroutine stops the threads.
 */
class SpeedCheckRunner(private val context: Context) {

    /** [onSecond]: after each window, every rate so far (units/s); [onTick]: ~6×/s, how far through and the rate now. */
    suspend fun measure(onSecond: (List<Double>) -> Unit, onTick: (Float, Double) -> Unit): SpeedMeasurement =
        withContext(Dispatchers.Default) {
            val threads = Runtime.getRuntime().availableProcessors()
            val ids = CpuTest.MULTI.kernelIds
            val calls = AtomicLong(0)
            val stop = AtomicBoolean(false)
            val error = AtomicReference<Throwable?>(null)
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val workers = (0 until threads).map { n ->
                Thread {
                    val kernels = ArrayList<NativeKernel>()
                    var counted = false
                    try {
                        for (id in ids) kernels += NativeKernel(id)
                        val expected = kernels.map { it.run() }              // also fills the caches before the clock starts
                        ready.countDown(); counted = true
                        go.await()
                        var k = 0
                        while (!stop.get()) {
                            check(kernels[k].run() == expected[k]) { "${kernels[k].name} result changed" }
                            calls.incrementAndGet()
                            k = (k + 1) % kernels.size
                        }
                    } catch (t: Throwable) {
                        error.compareAndSet(null, t)
                        if (!counted) ready.countDown()
                    } finally {
                        kernels.forEach { it.close() }
                    }
                }.apply { name = "th-speed-$n"; start() }
            }
            try {
                ready.await()
                error.get()?.let { throw it }
                val windows = ArrayList<Double>()
                val t0 = System.nanoTime()
                go.countDown()
                var lastCalls = 0L
                var lastT = t0
                for (sec in 1..SpeedCheck.SECONDS) {
                    val end = t0 + sec * 1_000_000_000L
                    while (true) {
                        ensureActive()
                        val left = end - System.nanoTime()
                        if (left <= 0) break
                        delay((left / 1_000_000L).coerceIn(1L, 160L))
                        val now = System.nanoTime()
                        val rate = (calls.get() - lastCalls) / ids.size.toDouble() / ((now - lastT).coerceAtLeast(1L) / 1e9)
                        onTick(((now - t0) / 1e9f / SpeedCheck.SECONDS).coerceIn(0f, 1f), rate)
                    }
                    val now = System.nanoTime()
                    val c = calls.get()
                    windows += (c - lastCalls) / ids.size.toDouble() / ((now - lastT) / 1e9)
                    lastCalls = c; lastT = now
                    error.get()?.let { throw it }
                    onSecond(windows.toList())
                }
                stop.set(true)
                SpeedMeasurement(windows, powerSaver(), charging(), thermalStatus(), System.currentTimeMillis())
            } finally {
                stop.set(true)
                go.countDown()
                workers.forEach { it.join() }
            }
        }

    private fun powerSaver(): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (_: Exception) { false }

    /** On a charger (any kind) — plugged in, whatever the battery level. */
    private fun charging(): Boolean = try {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (_: Exception) { false }

    /** PowerManager's own thermal status (0 none … 6 shutdown); −1 before Android 10, which can't tell. */
    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } catch (_: Exception) { -1 } else -1
}
