package com.example.thermohammer.engine.llm

import android.app.Service
import com.example.thermohammer.engine.LlmNpuRun
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.benchmark
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test builds only: an LLM on the phone's NPU through Google's LiteRT-LM — the first step of the scoring-version-5 plan
 * (BENCHMARK_V5_PLAN.md): the LLM test's NPU engine, a third of its score (LlmResult.npuPoints).
 *
 * The model is Google's own per-chip build, Gemma3-1B-IT_q4_ekv1280_<soc>.litertlm (4-bit, a 1,280-token context; the
 * same model and precision for Snapdragon 8 Elite `sm8750` and Dimensity 9500 `mt6993`), from
 * huggingface.co/litert-community/Gemma3-1B-IT behind Gemma's licence: it isn't in the app. It goes into [modelDir] —
 * opened with "ThermoHammer Bench" from the phone's Files app (NpuModelImportActivity) or copied there by adb.
 *
 * Speed: LiteRT-LM's own benchmark with the LLM test's task size, a 512-token prompt and a 32-token reply.
 * Answer: one fixed question, greedy (top-k 1), whose reply must name the expected word.
 *
 * LiteRT-LM 0.19 loads NPU dispatch libraries with the versioned dispatch ABI, which LiteRT 2.4's have and the scored
 * AI test's 2.3 ones don't: 2.4's come as assets (build.gradle.kts) and go into a folder of their own, linked to the QNN
 * runtime the app bundles. It all runs in a process of its own (NpuLlmProbeService, ":npullm", like DriverCheck): a
 * native crash in a driver or in LiteRT-LM ends that process, not the benchmark — and LiteRT-LM never shares a
 * process with the scored AI test's LiteRT 2.3.
 */
object NpuLlmProbe {
    const val AVAILABLE = true
    const val MSG_RUN = 1        // app → service: run the probe
    const val MSG_STEP = 2       // service → app: "step" (label) and "fraction"
    const val MSG_DONE = 3       // service → app: "lines", the report lines
    /** How long one step (loading the model on the NPU, the benchmark, the answer) may take before it counts as hung. */
    private const val STEP_TIMEOUT_MS = 240_000L
    private const val HEAD = "LLM NPU · LiteRT-LM 0.19.1"

    /**
     * [run] in the probe's own process; `onStep` on the main thread. Its report lines, or one saying the process
     * crashed or hung on which step.
     */
    suspend fun runIsolated(context: Context, onStep: (String, Float) -> Unit): LlmNpuRun = withContext(Dispatchers.Main.immediate) {
        fun failed(why: String) = LlmNpuRun(0.0, 0.0, false, error = why, lines = listOf("$HEAD · $why"))
        val done = CompletableDeferred<LlmNpuRun>()
        var step = "starting"
        var watchdog: Job? = null
        fun arm() {
            watchdog?.cancel()
            watchdog = launch { delay(STEP_TIMEOUT_MS); done.complete(failed("stopped: no answer for ${STEP_TIMEOUT_MS / 1000} s on: $step")) }
        }
        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                MSG_STEP -> { step = msg.data.getString("step") ?: step; onStep(step, msg.data.getFloat("fraction")); arm() }
                MSG_DONE -> msg.data.let { d ->
                    done.complete(LlmNpuRun(d.getDouble("prefill"), d.getDouble("decode"), d.getBoolean("right"),
                        d.getString("model"), d.getString("error"), d.getStringArrayList("lines")?.toList().orEmpty()))
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching { Messenger(binder).send(Message.obtain(null, MSG_RUN).apply { replyTo = replies }) }
                    .onFailure { done.complete(failed("not run: ${it.message ?: it.javaClass.simpleName}")) }
                arm()
            }
            override fun onServiceDisconnected(name: ComponentName) { done.complete(failed("its process crashed on: $step")) }
            override fun onBindingDied(name: ComponentName) { done.complete(failed("its process crashed on: $step")) }
            override fun onNullBinding(name: ComponentName) { done.complete(failed("not run: its service didn't start")) }
        }
        val bound = runCatching {
            context.bindService(Intent(context, NpuLlmProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            return@withContext failed("not run: its process couldn't be started")
        }
        arm()
        try {
            done.await()
        } finally {
            watchdog?.cancel()
            runCatching { context.unbindService(connection) }      // the service ends its process when let go
        }
    }
    private const val PREFILL = 512
    private const val DECODE = 32
    private const val QUESTION = "What is the capital of France? Answer with one word."
    private const val EXPECTED = "paris"
    /** A per-chip build's name: sm8750, mt6993, Google_Tensor_G5 … */
    private val CHIP_BUILD = Regex("(sm|mt)\\d{4}|tensor", RegexOption.IGNORE_CASE)

    /** Where the model file goes: the app's own folder on shared storage (adb can write it; no permission needed). */
    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "npu-llm").apply { mkdirs() }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.lowercase() else ""

    private fun vendor(soc: String): String? = when {
        soc.startsWith("sm") -> "Qualcomm"
        soc.startsWith("mt") -> "MediaTek"
        else -> null
    }

    /**
     * The folder LiteRT-LM's NPU backend loads from: LiteRT 2.4's dispatch library for this vendor (only that one —
     * LiteRT takes the first dispatch library it finds) and links to the vendor runtime the app carries (Qualcomm's
     * QNN libraries; MediaTek's adapter where a build bundles it, else the phone's own).
     */
    private fun libraryDir(context: Context, vendor: String): Pair<File, String> {
        val dir = File(context.codeCacheDir, "litertlm-npu").apply { deleteRecursively(); mkdirs() }
        val name = "libLiteRtDispatch_$vendor.so"
        context.assets.open("litertlm-npu/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        var linked = 0
        for (lib in File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()) {
            if (lib.name.startsWith("libQnn") || lib.name.startsWith("libneuron")) {
                android.system.Os.symlink(lib.path, File(dir, lib.name).path)
                linked++
            }
        }
        return dir to "LiteRT 2.4 dispatch library + $linked of the app's runtime libraries"
    }

    private fun Throwable.why(): String = (message ?: javaClass.simpleName).lines().first().take(240)

    /**
     * What this process logged at warning level and above (LiteRT-LM, LiteRT, the dispatch library, QNN, NeuroPilot):
     * the reason behind LiteRT-LM's one-line errors, for the report — a phone tested far away has no adb. The process
     * is the probe's own, so its log is the probe's. An app may read its own log without any permission.
     */
    private fun ownLog(max: Int = 14): List<String> = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "--pid=${android.os.Process.myPid()}", "*:W")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        out.filter { it.length > 2 && it[1] == '/' && it[0] in "WEF" && !it.contains("NpuLlmProbe") }
            .map { it.replace(Regex("\\(\\s*\\d+\\):\\s*"), ": ").take(220) }
            .distinct().takeLast(max)
    }.getOrElse { listOf("couldn't read its log: ${it.message ?: it.javaClass.simpleName}") }

    /** `onStep(label, fraction)` for the stage. Never throws (a native crash is the caller's marker's business). */
    @OptIn(ExperimentalApi::class)          // LiteRT-LM's benchmark(): what times the prompt and the reply
    fun run(context: Context, isActive: () -> Boolean, onStep: (String, Float) -> Unit): LlmNpuRun {
        fun notRun(line: String) = LlmNpuRun(0.0, 0.0, false, error = line.substringAfter(" · not run: ", line), lines = listOf(line))
        val head = HEAD
        val soc = soc()
        val vendor = vendor(soc)
        val dir = modelDir(context)
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".litertlm") }
        // a chip with an NPU path takes its own chip's build; any other phone (the emulator) can run a generic model on
        // the CPU instead — a smoke test that LiteRT-LM loads and runs in this build at all
        val model = (if (vendor != null) files.filter { soc in it.name.lowercase() }
            else files.filterNot { CHIP_BUILD.containsMatchIn(it.name) }).maxByOrNull { it.lastModified() }
            ?: return notRun(if (vendor == null) "$head · not run: no NPU path for the chip '${soc.ifBlank { "?" }}'"
                else "$head · not run: no model file for $soc in $dir — Gemma3-1B-IT_q4_ekv1280_$soc.litertlm " +
                    "from huggingface.co/litert-community/Gemma3-1B-IT, opened with ThermoHammer Bench")
        val backend: Backend
        val where: String
        if (vendor != null) {
            val (libs, libsNote) = try { libraryDir(context, vendor) } catch (e: Exception) {
                return notRun("$head · not run: the NPU libraries couldn't be prepared: ${e.why()}")
            }
            backend = Backend.NPU(libs.path); where = "$vendor NPU · $libsNote"
        } else {
            backend = Backend.CPU(); where = "CPU (no NPU path for the chip '${soc.ifBlank { "?" }}': a smoke test, not an NPU result)"
        }
        val lines = ArrayList<String>()
        lines += "$head · ${model.name} (${model.length() / 1_000_000} MB) · $where"

        onStep("${if (vendor != null) "NPU" else "CPU"} · LITERT-LM · GEMMA 3 1B · SPEED", 0.1f)
        var prefill = 0.0
        var decode = 0.0
        var right = false
        var error: String? = null
        try {
            val info = benchmark(modelPath = model.path, backend = backend,
                prefillTokens = PREFILL, decodeTokens = DECODE, cacheDir = context.cacheDir.path)
            prefill = info.lastPrefillTokensPerSecond; decode = info.lastDecodeTokensPerSecond
            lines += "  speed · prompt %.2f tok/s (%d tokens) · reply %.2f tok/s (%d tokens) · first token %.2f s · load %.2f s".format(
                info.lastPrefillTokensPerSecond, info.lastPrefillTokenCount, info.lastDecodeTokensPerSecond,
                info.lastDecodeTokenCount, info.timeToFirstTokenInSecond, info.initTimeInSecond)
        } catch (e: Throwable) {
            lines += "  speed · failed: ${e.why()}"
            error = "speed: ${e.why()}"
        }
        if (!isActive()) return LlmNpuRun(prefill, decode, false, model.name, error ?: "stopped", lines)

        onStep("${if (vendor != null) "NPU" else "CPU"} · LITERT-LM · GEMMA 3 1B · ANSWER CHECK", 0.7f)
        try {
            Engine(EngineConfig(modelPath = model.path, backend = backend, cacheDir = context.cacheDir.path)).use { engine ->
                engine.initialize()
                val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 1),
                    maxOutputToken = 16)
                engine.createConversation(config).use { conversation ->
                    val reply = conversation.sendMessage(QUESTION).contents.contents
                        .filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                    val ok = EXPECTED in reply.lowercase()
                    right = ok
                    lines += "  answer · \"$QUESTION\" → \"${reply.replace('\n', ' ').take(80)}\" · ${if (ok) "right" else "WRONG (expected Paris)"}"
                }
            }
        } catch (e: Throwable) {
            lines += "  answer · failed: ${e.why()}"
            if (error == null) error = "answer: ${e.why()}"
        }
        // a failure's real reason is in the log, not in LiteRT-LM's one line
        if (lines.any { "· failed:" in it }) ownLog().forEach { lines += "  log · $it" }
        // a CPU smoke test (no NPU path for the chip) is never an NPU result
        if (vendor == null) return LlmNpuRun(0.0, 0.0, false, model.name, "no NPU path for this chip", lines)
        return LlmNpuRun(prefill, decode, right, model.name, error, lines)
    }
}

/** The probe's own process (":npullm", test-build manifests): it exists only while the app is bound to it. */
class NpuLlmProbeService : Service() {
    private val worker = HandlerThread("npu-llm-probe").apply { start() }
    private val messenger by lazy {
        Messenger(Handler(worker.looper) { msg ->
            if (msg.what == NpuLlmProbe.MSG_RUN) {
                val reply = msg.replyTo
                val r = NpuLlmProbe.run(this, { true }) { label, fraction ->
                    runCatching {
                        reply?.send(Message.obtain(null, NpuLlmProbe.MSG_STEP).apply {
                            data = Bundle().apply { putString("step", label); putFloat("fraction", fraction) }
                        })
                    }
                }
                runCatching {
                    reply?.send(Message.obtain(null, NpuLlmProbe.MSG_DONE).apply {
                        data = Bundle().apply {
                            putStringArrayList("lines", ArrayList(r.lines)); putDouble("prefill", r.prefill); putDouble("decode", r.decode)
                            putBoolean("right", r.right); putString("model", r.model); putString("error", r.error)
                        }
                    })
                }
            }
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        // whatever happens to the app, this process doesn't outstay the probe
        Handler(Looper.getMainLooper()).postDelayed({ exit() }, 15 * 60_000L)
    }

    /**
     * The probe alone, its lines to logcat (tag NpuLlmProbe) — for checking it on an emulator or phone without a whole
     * benchmark: `adb shell am start -n <package>/com.example.thermohammer.NpuModelImportActivity
     * -a com.example.thermohammer.RUN_NPU_LLM_PROBE` with the app open (the service isn't exported; the activity is, and
     * starts it). Background limits stop a started service about a minute after its app leaves the screen.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RUN) Handler(worker.looper).post {
            // error level: some phones (Honor) keep nothing below it from an app
            NpuLlmProbe.run(this, { true }) { label, _ -> android.util.Log.e("NpuLlmProbe", "step: $label") }
                .lines.forEach { android.util.Log.e("NpuLlmProbe", it) }
            android.util.Log.e("NpuLlmProbe", "done")
            exit()
        }
        return START_NOT_STICKY
    }

    companion object { const val ACTION_RUN = "com.example.thermohammer.RUN_NPU_LLM_PROBE" }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { exit(); return false }
    override fun onDestroy() { exit() }
    private fun exit() = android.os.Process.killProcess(android.os.Process.myPid())
}
