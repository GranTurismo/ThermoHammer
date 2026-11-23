package com.example.thermohammer.engine.ai

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest

/**
 * Test builds only: why an Adreno GPU's AI answers aren't the CPU's (every Snapdragon since the check arrived in
 * 2.1.5; FP32 doesn't help, see BENCHMARK_V5_PLAN.md). Every model, on the CPU and on each of LiteRT's ways of running
 * the GPU, with two different inputs: whether each answer is the CPU's for the same input, and whether the GPU's
 * answer moves at all when its input does. Logcat only (tag AiGpuDiag, error level: Honor keeps nothing below it).
 *
 *   adb shell am start -n <package>/com.example.thermohammer.NpuModelImportActivity -a com.example.thermohammer.RUN_AI_GPU_DIAG
 *
 * with the app open. It runs in a process of its own (":aidiag"): a fresh GPU accelerator, nothing left from a run.
 */
object AiGpuDiagnosis {
    private const val TAG = "AiGpuDiag"
    private const val SECONDS = 0.05          // a few inferences after the warm-up: answers, not speed

    private fun log(line: String) = Log.e(TAG, line)

    /** The second input: the first, rolled by about a third (whole pixels), so the content moves. */
    private fun other(a: ByteArray): ByteArray {
        val k = (a.size / 3).let { it - it % 12 }
        return a.copyOfRange(k, a.size) + a.copyOfRange(0, k)
    }

    private fun stats(o: ByteArray): String {
        var sum = 0L; var zeros = 0
        val seen = BooleanArray(256)
        for (b in o) { val v = b.toInt() and 0xFF; sum += v; if (v == 0) zeros++; seen[v] = true }
        return "mean %.1f, %d values, %.0f%% zero".format(sum.toDouble() / o.size, seen.count { it }, 100.0 * zeros / o.size)
    }

    private val SETTINGS: List<Pair<String, AiSetting?>> = listOf(
        "default" to null,
        "default again" to null,                          // the same model compiled a second time in this process
        "OpenCL" to AiSetting("opencl", "OpenCL backend", gpuBackend = GpuBackend.OPENCL),
        "OpenGL" to AiSetting("opengl", "OpenGL ES backend", gpuBackend = GpuBackend.OPENGL),
        "buffers" to AiSetting("buffers", "buffer storage", gpuStorage = GpuStorage.BUFFER),
        "textures" to AiSetting("textures", "2D texture storage", gpuStorage = GpuStorage.TEXTURE_2D),
        "FP32" to AiSetting("fp32", "FP32", gpuPrecision = GpuPrecision.FP32),
        "FP32+buffers" to AiSetting("fp32-buffers", "FP32, buffers", gpuPrecision = GpuPrecision.FP32, gpuStorage = GpuStorage.BUFFER),
        "OpenGL+FP32" to AiSetting("opengl-fp32", "OpenGL, FP32", gpuBackend = GpuBackend.OPENGL, gpuPrecision = GpuPrecision.FP32),
        "int8 input" to AiSetting("int8-input", "8-bit input quantization", srcQuantized = true))

    /**
     * The GPU's default way (OpenCL on an Adreno) driven step by step: is the answer there when it's read at once, a
     * moment later, after another run, in output buffers LiteRT allocates itself, after a new input? Each compared with
     * the CPU's answer for the same input. Says whether the GPU computes wrongly or its results just don't reach the app.
     */
    fun deep(context: android.content.Context, only: Set<String>? = null) {
        val bench = AiBench(context)
        val inputs = AiInputs(context)
        val env = com.google.ai.edge.litert.Environment.create()
        for (test in AiTest.entries) {
            if (only != null && test.name.lowercase() !in only) continue
            val a = inputs.input(test).first
            val b = other(a)
            val cpuA = bench.run(test, AiAccel.CPU, a, SECONDS, { true }, true, null) { _, _ -> }.outputs
            val cpuB = bench.run(test, AiAccel.CPU, b, SECONDS, { true }, true, null) { _, _ -> }.outputs
            val name = test.name.lowercase()
            fun vs(tag: String, got: List<ByteArray>) = log("  %-34s A %.2f · B %.2f · %s".format(tag,
                AiAgreement.of(test, cpuA, got), AiAgreement.of(test, cpuB, got), stats(got[0])))
            try {
                com.google.ai.edge.litert.CompiledModel.create(context.assets, test.asset,
                    com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.GPU), env).use { model ->
                    log("$name · deep · each line: agreement with the CPU's answer to input A, to input B")
                    val ins = model.createInputBuffers()
                    val outs = model.createOutputBuffers()
                    ins[0].writeInt8(a)
                    vs("outputs before any run", outs.map { it.readInt8() })
                    model.run(ins, outs)
                    vs("1 run on A, read at once", outs.map { it.readInt8() })
                    Thread.sleep(300)
                    vs("same, read again 300 ms later", outs.map { it.readInt8() })
                    model.run(ins, outs)
                    vs("2nd run on A", outs.map { it.readInt8() })
                    repeat(10) { model.run(ins, outs) }
                    vs("12th run on A", outs.map { it.readInt8() })
                    val fresh = model.run(ins)
                    vs("run on A, LiteRT's own outputs", fresh.map { it.readInt8() })
                    fresh.forEach { it.close() }
                    ins[0].writeInt8(b)
                    model.run(ins, outs)
                    vs("input B written, 1 run", outs.map { it.readInt8() })
                    val fresh2 = model.run(ins)
                    vs("run on B, LiteRT's own outputs", fresh2.map { it.readInt8() })
                    fresh2.forEach { it.close() }
                    // a new input buffer each time, written before the run
                    val ins2 = model.createInputBuffers()
                    ins2[0].writeInt8(a)
                    val outs2 = model.createOutputBuffers()
                    model.run(ins2, outs2)
                    vs("new buffers, A, 1 run", outs2.map { it.readInt8() })
                    (ins + outs + ins2 + outs2).forEach { it.close() }
                }
            } catch (e: Throwable) {
                log("$name · deep failed: ${(e.message ?: e.javaClass.simpleName).lines().first().take(200)}")
            }
        }
        log("done")
    }

    /** CPU ticks (utime + stime) of each of this process's threads, by "tid name". */
    private fun threadTicks(): Map<String, Long> = java.io.File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
        runCatching {
            val stat = java.io.File(t, "stat").readText()
            val f = stat.substringAfterLast(") ").split(' ')
            val name = java.io.File(t, "comm").readText().trim()
            "${t.name} $name" to (f[11].toLong() + f[12].toLong())       // fields 14 and 15 of stat, after "pid (comm) "
        }.getOrNull()
    }.toMap()

    /** Threads that used CPU while this one slept `ms`: what is busy behind the app's back. */
    private fun busyWhileIdle(ms: Long): String {
        val a = threadTicks()
        Thread.sleep(ms)
        val b = threadTicks()
        val busy = b.mapNotNull { (k, v) -> val d = v - (a[k] ?: v); if (d > 2) k to d else null }.sortedByDescending { it.second }
        return if (busy.isEmpty()) "no thread busy" else busy.take(8).joinToString(", ") { "${it.first} ${it.second * 10} ms" }
    }

    /**
     * Whether a GPU run that never delivers (LiteRT's OpenCL on Adreno) leaves work behind that slows the CPU: the CPU's
     * rate on two models before and after such runs, and which threads use CPU while the app sleeps.
     */
    fun hog(context: android.content.Context) {
        val bench = AiBench(context)
        val inputs = AiInputs(context)
        fun cpu(test: AiTest) = runCatching { bench.run(test, AiAccel.CPU, inputs.input(test).first, 1.5, { true }, true, null) { _, _ -> }.ips }.getOrDefault(-1.0)
        log("hog · idle at start: ${busyWhileIdle(2000)}")
        log("hog · CPU before: upscale %.2f/s · depth %.2f/s".format(cpu(AiTest.UPSCALE), cpu(AiTest.DEPTH)))
        log("hog · idle after the CPU runs: ${busyWhileIdle(2000)}")
        for (test in listOf(AiTest.CLASSIFY, AiTest.SEGMENT)) {
            val r = runCatching { bench.run(test, AiAccel.GPU, inputs.input(test).first, 1.0, { true }, true, null) { _, _ -> } }
            log("hog · GPU default ${test.name.lowercase()}: ${r.exceptionOrNull()?.message ?: "ran %.2f/s".format(r.getOrNull()?.ips ?: 0.0)}")
        }
        log("hog · idle after the GPU runs: ${busyWhileIdle(2000)}")
        log("hog · CPU after: upscale %.2f/s · depth %.2f/s".format(cpu(AiTest.UPSCALE), cpu(AiTest.DEPTH)))
        log("hog · idle 5 s later: ${busyWhileIdle(2000)}")
        log("done")
    }

    /**
     * Whether a model that fails to compile on LiteRT's OpenGL backend breaks the next OpenGL compiles (the Honor's
     * scored run: detect failed on OpenGL, then segment, upscale, depth and sound did too), and what repairs it: a new
     * LiteRT environment, or a run on the default (OpenCL) backend in between.
     */
    fun glState(context: android.content.Context) {
        val inputs = AiInputs(context)
        val gl = AiSetting("opengl", "OpenGL ES backend", gpuBackend = GpuBackend.OPENGL)
        fun tryRun(bench: AiBench, test: AiTest, s: AiSetting?, tag: String) {
            val r = runCatching { bench.run(test, AiAccel.GPU, inputs.input(test).first, SECONDS, { true }, true, s) { _, _ -> } }
            log("gl · $tag · ${test.name.lowercase()} on ${s?.id ?: "default"}: " +
                (r.exceptionOrNull()?.message?.lines()?.first()?.take(120) ?: "ran %.1f/s".format(r.getOrNull()!!.ips)))
        }
        val a = AiBench(context)
        tryRun(a, AiTest.SEGMENT, gl, "1 fresh")
        tryRun(a, AiTest.DETECT, gl, "2 same env")
        tryRun(a, AiTest.SEGMENT, gl, "3 same env, after the failure")
        tryRun(a, AiTest.UPSCALE, gl, "4 same env")
        val b = AiBench(context)
        tryRun(b, AiTest.SEGMENT, gl, "5 NEW env")
        tryRun(b, AiTest.DETECT, gl, "6 new env")
        tryRun(b, AiTest.CLASSIFY, null, "7 new env, an OpenCL run in between")
        tryRun(b, AiTest.SEGMENT, gl, "8 after the OpenCL run")
        log("done")
    }

    /**
     * The scored run's own order (each model: CPU, GPU on OpenGL, NPU) for classify, detect and segment, with or without
     * a short run on the GPU's default after OpenGL fails — whether that run is what keeps the next OpenGL compile working.
     */
    fun sequence(context: android.content.Context, reset: Boolean) {
        val bench = AiBench(context)
        val inputs = AiInputs(context)
        val gl = AiSetting("opengl", "OpenGL ES backend", gpuBackend = GpuBackend.OPENGL)
        log("seq · reset after an OpenGL failure: $reset")
        for (test in listOf(AiTest.CLASSIFY, AiTest.DETECT, AiTest.SEGMENT, AiTest.UPSCALE)) {
            val input = inputs.input(test).first
            fun go(accel: AiAccel, s: AiSetting?, secs: Double = SECONDS) =
                runCatching { bench.run(test, accel, input, secs, { true }, true, s) { _, _ -> } }
            go(AiAccel.CPU, null)
            val g = go(AiAccel.GPU, gl)
            log("seq · ${test.name.lowercase()} GPU OpenGL: " + (g.exceptionOrNull()?.message?.lines()?.first()?.take(80) ?: "ran %.1f/s".format(g.getOrNull()!!.ips)))
            if (g.isFailure && reset) {
                val r = go(AiAccel.GPU, null, 1.0)
                log("seq · ${test.name.lowercase()} reset run on the default: " + (r.exceptionOrNull()?.message?.lines()?.first()?.take(60) ?: "ran"))
            }
            val n = go(AiAccel.NPU, null)
            log("seq · ${test.name.lowercase()} NPU: " + (n.exceptionOrNull()?.message?.lines()?.first()?.take(60) ?: "ran %.0f/s".format(n.getOrNull()!!.ips)))
        }
        log("done")
    }

    /**
     * Inside the update step: transactions per 100 ms over 2 s, round after round with a fresh database each — whether a
     * slow round is slow all along or fast with a few long stalls (then a median would measure the phone, not the stall).
     */
    fun databaseSlices(context: android.content.Context) {
        repeat(6) { round ->
            val file = java.io.File(context.cacheDir, "slices.db")
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL, sent INTEGER NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, unread INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX messages_chat_sent ON messages(chat, sent)")
                val rnd = kotlin.random.Random(7)
                val ins = db.compileStatement("INSERT INTO messages(chat, sent, sender, body, unread) VALUES (?, ?, ?, ?, 1)")
                db.beginTransaction()
                repeat(20_000) { ins.bindLong(1, rnd.nextInt(40).toLong()); ins.bindLong(2, it.toLong()); ins.bindString(3, "c"); ins.bindString(4, "see you at the station tomorrow photos from trip"); ins.executeInsert() }
                db.setTransactionSuccessful(); db.endTransaction()
                repeat(30_000) { ins.bindLong(1, rnd.nextInt(40).toLong()); ins.bindLong(2, it.toLong()); ins.bindString(3, "c"); ins.bindString(4, "see you"); ins.executeInsert() }
                val up = db.compileStatement("UPDATE messages SET unread = 0 WHERE id = ?")
                val buckets = IntArray(60)
                val t0 = System.nanoTime()
                var worst = 0L
                while (true) {
                    val t = System.nanoTime(); val i = ((t - t0) / 100_000_000L).toInt(); if (i >= 60) break
                    up.bindLong(1, 1 + rnd.nextLong(20_000)); up.executeUpdateDelete(); buckets[i]++
                    worst = maxOf(worst, System.nanoTime() - t)
                }
                val sorted = buckets.sorted()
                val firstFull = buckets.indexOfFirst { it >= 35_000 }
                log("db slices · round ${round + 1} · per 100 ms, every 5th: ${buckets.filterIndexed { i, _ -> i % 5 == 4 }.joinToString(" ")} · " +
                    "first 2 s %d/s · last 2 s %d/s · seconds to full speed %s".format(buckets.take(20).sum() / 2, buckets.takeLast(20).sum() / 2,
                    if (firstFull < 0) "never" else "%.1f".format(firstFull / 10.0)))
            } finally { db.close(); android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
        }
        log("done")
    }

    /** The database test alone, then after the storage test with a pause: is the storage test's after-work the swing? */
    fun databaseOrder(context: android.content.Context) {
        val mem = com.example.thermohammer.engine.mem.MemBench(context)
        fun one(tag: String) {
            val r = mem.database({ true }, { })
            log("db · $tag · insert %.0f · update %.0f · query %.0f tx/s".format(r.insert, r.update, r.query))
        }
        repeat(5) { one("alone, run ${it + 1}") }
        repeat(4) { mem.storage({ true }) { }; Thread.sleep(5_000); one("storage, then 5 s, run ${it + 1}") }
        repeat(3) { mem.storage({ true }) { }; one("storage, no pause, run ${it + 1}") }
        log("done")
    }

    /** The memory section's storage test then its database test, five times each way: how much the database rates swing. */
    fun database(context: android.content.Context) {
        val mem = com.example.thermohammer.engine.mem.MemBench(context)
        for (checkpoints in listOf("warm-up")) {
            repeat(5) { i ->
                mem.storage({ true }) { }
                val t = System.nanoTime()
                val r = mem.database({ true }, { })
                log("db · checkpoints $checkpoints · run ${i + 1} · insert %.0f · update %.0f · query %.0f tx/s · %.1f s".format(
                    r.insert, r.update, r.query, (System.nanoTime() - t) / 1e9))
            }
        }
        log("done")
    }

    /** One model file compiled (and run once) on the GPU's OpenGL backend: whether LiteRT can build it there. */
    fun compileFile(path: String, backend: String?) {
        val name = java.io.File(path).name
        try {
            val env = com.google.ai.edge.litert.Environment.create()
            val opts = com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.GPU).apply {
                gpuOptions = com.google.ai.edge.litert.CompiledModel.GpuOptions(backend =
                    if (backend == "opencl") com.google.ai.edge.litert.CompiledModel.GpuOptions.Backend.OPENCL
                    else com.google.ai.edge.litert.CompiledModel.GpuOptions.Backend.OPENGL)
            }
            com.google.ai.edge.litert.CompiledModel.create(path, opts, env).use { model ->
                val ins = model.createInputBuffers(); val outs = model.createOutputBuffers()
                model.run(ins, outs)
                log("compile · $name · ${backend ?: "opengl"} · OK")
                (ins + outs).forEach { it.close() }
            }
        } catch (e: Throwable) {
            log("compile · $name · ${backend ?: "opengl"} · FAILED: ${(e.message ?: e.javaClass.simpleName).lines().first().take(120)}")
        }
        log("done")
    }

    /** YOLOX's class output (ARG_MAX, 32-bit) on the CPU, read as bytes and as integers: what readInt8 gives for it. */
    fun classes(context: android.content.Context) {
        val inputs = AiInputs(context)
        val env = com.google.ai.edge.litert.Environment.create()
        com.google.ai.edge.litert.CompiledModel.create(context.assets, AiTest.DETECT.asset,
            com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.CPU), env).use { model ->
            val ins = model.createInputBuffers(); val outs = model.createOutputBuffers()
            ins[0].writeInt8(inputs.input(AiTest.DETECT).first); model.run(ins, outs)
            val b = outs[2].readInt8(); val i = runCatching { outs[2].readInt() }.getOrNull()
            log("classes · readInt8 ${b.size} bytes, first 12: ${b.take(12)} · readInt ${i?.size} ints, first 12: ${i?.take(12)}")
            (ins + outs).forEach { it.close() }
        }
        log("done")
    }

    fun run(context: android.content.Context, only: Set<String>? = null) {
        val bench = AiBench(context)
        val inputs = AiInputs(context)
        log("start · GPU available: ${AiAccel.GPU in bench.available()}")
        for (test in AiTest.entries) {
            if (only != null && test.name.lowercase() !in only) continue
            val a = inputs.input(test).first
            val b = other(a)
            fun go(accel: AiAccel, input: ByteArray, s: AiSetting?): List<ByteArray>? = try {
                bench.run(test, accel, input, SECONDS, { true }, true, s) { _, _ -> }.outputs
            } catch (e: Throwable) {
                log("${test.name.lowercase()} · ${accel.name} ${s?.id ?: "default"} failed: ${(e.message ?: e.javaClass.simpleName).lines().first().take(200)}")
                null
            }
            val cpuA = go(AiAccel.CPU, a, null) ?: continue
            val cpuB = go(AiAccel.CPU, b, null) ?: continue
            log("${test.name.lowercase()} · ${cpuA.size} output(s) of ${cpuA.joinToString("+") { it.size.toString() }} bytes · " +
                "CPU: input A vs B answers %.2f (how much the answer moves) · A: %s".format(AiAgreement.of(test, cpuA, cpuB), stats(cpuA[0])))
            for ((name, s) in SETTINGS) {
                val gA = go(AiAccel.GPU, a, s) ?: continue
                val gB = go(AiAccel.GPU, b, s) ?: continue
                log("  %-13s · A vs CPU %.2f · B vs CPU %.2f · GPU A vs GPU B %.2f · A vs CPU's B %.2f · A: %s".format(name,
                    AiAgreement.of(test, cpuA, gA), AiAgreement.of(test, cpuB, gB), AiAgreement.of(test, gA, gB),
                    AiAgreement.of(test, cpuB, gA), stats(gA[0])))
                // per output, where a model has several
                if (gA.size > 1) log("                  per output, A vs CPU: " +
                    gA.indices.joinToString(" · ") { "%d: %.2f".format(it, AiAgreement.correlation(cpuA[it], gA[it])) })
            }
        }
        log("done")
    }
}

/**
 * Test builds only: llama.cpp on Gemma 3 1B's shape, on the CPU (dot-product kernels, 6 threads, no tuning beyond
 * LlmBench's own) and the GPU, without a whole benchmark — the quick check that the shape builds and runs on a phone's
 * backends. Logcat, tag GemmaShape. Started like the diagnosis, with action RUN_GEMMA_LLM.
 */
object GemmaShapeQuick {
    fun run(context: android.content.Context) {
        val bench = com.example.thermohammer.engine.llm.LlmBench(context)
        for (accel in com.example.thermohammer.engine.LlmAccel.entries) {
            val t0 = System.nanoTime()
            val r = try {
                bench.run(accel, 6, { true }, com.example.thermohammer.engine.llm.DotprodLib, emptyList(),
                    model = com.example.thermohammer.engine.llm.LlmNative.MODEL_GEMMA3_1B) { }
            } catch (e: Throwable) { null.also { Log.e("GemmaShape", "${accel.name} threw $e") } }
            Log.e("GemmaShape", "${accel.name} · " + when {
                r == null -> "no result"
                r.error != null -> "failed: ${r.error}"
                else -> "prompt %.2f tok/s · reply %.2f tok/s · threads %d/%d".format(r.prefill, r.decode, r.threads, r.batchThreads)
            } + " · %.1f s · GPU %s".format((System.nanoTime() - t0) / 1e9, bench.gpuName()))
        }
        Log.e("GemmaShape", "done")
    }
}

/**
 * Test builds only: the scored LLM CPU test's thread search (Llama 3.2 3B shape, the i8mm build where the CPU has i8mm,
 * pinning tried on the fast cores), twice in a fresh process, every trial logged — whether a trial stalls (tag LlmTune).
 */
object LlmTuneQuick {
    fun run(context: android.content.Context) {
        val bench = com.example.thermohammer.engine.llm.LlmBench(context)
        val topology = com.example.thermohammer.engine.CpuTopologyReader.read()
        val cores = if (topology.canPin) topology.big else emptyList()
        val caps = com.example.thermohammer.engine.llm.LlmNative.hwcaps()
        val i8 = com.example.thermohammer.engine.llm.LlmNativeI8mm.loaded &&
            com.example.thermohammer.engine.llm.CpuFeatures.supportsI8mm(caps[0], caps[1])
        val lib = if (i8) com.example.thermohammer.engine.llm.I8mmLib else com.example.thermohammer.engine.llm.DotprodLib
        Log.e("LlmTune", "cores $cores · ${lib.variant}")
        repeat(2) { round ->
            val t0 = System.nanoTime()
            val r = bench.run(com.example.thermohammer.engine.LlmAccel.CPU, 4, { true }, lib, cores) { }
            Log.e("LlmTune", "round ${round + 1} · %.1f s · prompt %.2f · reply %.2f · %s".format((System.nanoTime() - t0) / 1e9,
                r?.prefill ?: 0.0, r?.decode ?: 0.0, r?.error ?: "ok"))
            r?.tune?.lines()?.forEach { Log.e("LlmTune", "  $it") }
        }
        Log.e("LlmTune", "done")
    }
}

/** The diagnosis's own process (":aidiag", test-build manifests); it ends when the diagnosis does. */
class AiGpuDiagnosisService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GEMMA) {
            val worker = HandlerThread("gemma-shape").apply { start() }
            Handler(worker.looper).post {
                try {
                    if (intent.getStringExtra("mode") == "tune") LlmTuneQuick.run(this) else GemmaShapeQuick.run(this)
                } catch (e: Throwable) { Log.e("GemmaShape", "crashed: $e") }
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        if (intent?.action == ACTION_RUN) {
            val only = intent.getStringExtra("only")?.split(',')?.map { it.trim().lowercase() }?.toSet()
            val worker = HandlerThread("ai-gpu-diag").apply { start() }
            Handler(worker.looper).post {
                try {
                    when (intent.getStringExtra("mode")) {
                        "deep" -> AiGpuDiagnosis.deep(this, only)
                        "hog" -> AiGpuDiagnosis.hog(this)
                        "gl" -> AiGpuDiagnosis.glState(this)
                        "seq" -> AiGpuDiagnosis.sequence(this, reset = false)
                        "db" -> AiGpuDiagnosis.database(this)
                        "classes" -> AiGpuDiagnosis.classes(this)
                        "compile" -> AiGpuDiagnosis.compileFile(intent.getStringExtra("file").orEmpty(), intent.getStringExtra("backend"))
                        "seq-reset" -> AiGpuDiagnosis.sequence(this, reset = true)
                        else -> AiGpuDiagnosis.run(this, only)
                    }
                } catch (e: Throwable) { Log.e("AiGpuDiag", "crashed: $e") }
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RUN = "com.example.thermohammer.RUN_AI_GPU_DIAG"
        const val ACTION_GEMMA = "com.example.thermohammer.RUN_GEMMA_LLM"
    }
}
