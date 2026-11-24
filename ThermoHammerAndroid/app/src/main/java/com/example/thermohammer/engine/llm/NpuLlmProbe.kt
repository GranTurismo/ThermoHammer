package com.example.thermohammer.engine.llm

import android.app.Service
import com.example.thermohammer.engine.LlmNpuRun
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
 * The LLM test's NPU engine (scoring version 5, BENCHMARK_V5_PLAN.md): Gemma 3 1B on the phone's NPU through Google's
 * LiteRT-LM — a third of the LLM score (LlmResult.npuPoints).
 *
 * The model is Google's own per-chip build, Gemma3-1B-IT_q4_ekv1280_<soc>.litertlm (4-bit, a 1,280-token context; the
 * same model and precision on every supported Snapdragon and Dimensity), delivered by Play as an AI pack (NpuModels).
 *
 * Speed: LiteRT-LM's own benchmark with the LLM test's task size, a 512-token prompt and a 32-token reply.
 * Answer: one fixed question, greedy (top-k 1), whose reply must name the expected word; a wrong one scores 0.
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
    /** The engine and its version, as the run records it (BenchmarkRecord.llmNpu): an update can move NPU scores. */
    const val ENGINE = "LiteRT-LM 0.19.1"
    private const val HEAD = "LLM NPU · $ENGINE"

    /**
     * [run] in the probe's own process; `onStep` on the main thread. Its report lines, or one saying the process
     * crashed or hung on which step.
     */
    /**
     * `model`: a file to run instead of the chip's own (test builds: Google's generic build, compiled on the device);
     * `stepTimeoutMs`: how long one step may go without news (an on-device compile of a whole LLM can take minutes).
     */
    suspend fun runIsolated(context: Context, model: File? = null, stepTimeoutMs: Long = STEP_TIMEOUT_MS,
                            onStep: (String, Float) -> Unit): LlmNpuRun = withContext(Dispatchers.Main.immediate) {
        // the model is found here, in the app's process (Play's AI pack), and handed to the engine's process
        val soc = NpuModels.soc()
        @Suppress("NAME_SHADOWING") val model = model ?: NpuModels.modelFile(context)
        // a failure once the file was found (its process crashed or hung, the libraries didn't load) keeps the model's
        // name: the NPU scores 0 and the run stays complete — the phone has its file, the NPU just couldn't run it
        fun failed(why: String) = LlmNpuRun(0.0, 0.0, false, model = model?.name, error = why, lines = listOf("$HEAD · $why"))
        if (model == null) {
            val supported = NpuModels.supported(context, soc)
            val why = if (supported)
                "not run: the NPU model for this chip (${NpuModels.fileName(soc)}) isn't on the phone — Google Play " +
                    "downloads it after the install; this run can't be submitted"
            else "not run: Google builds no Gemma 3 1B NPU model for this chip (${soc.ifBlank { "unknown" }}) — counts 0"
            return@withContext LlmNpuRun(0.0, 0.0, false, error = why.removePrefix("not run: "), lines = listOf("$HEAD · $why"),
                missingModel = supported)
        }
        val done = CompletableDeferred<LlmNpuRun>()
        var step = "starting"
        var watchdog: Job? = null
        fun arm() {
            watchdog?.cancel()
            watchdog = launch { delay(stepTimeoutMs); done.complete(failed("stopped: no answer for ${stepTimeoutMs / 1000} s on: $step")) }
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
                runCatching { Messenger(binder).send(Message.obtain(null, MSG_RUN).apply {
                    replyTo = replies; data = Bundle().apply { putString("model", model.path) } }) }
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
        // the vendor's on-device compiler: a model not built for this chip ahead of time is compiled here (JIT), once,
        // and LiteRT-LM caches the result in its cache folder
        val plugin = "libLiteRtCompilerPlugin_$vendor.so"
        val hasPlugin = runCatching {
            context.assets.open("litertlm-npu/$plugin").use { input -> File(dir, plugin).outputStream().use { input.copyTo(it) } }
        }.isSuccess
        var linked = 0
        for (lib in File(context.applicationInfo.nativeLibraryDir).listFiles().orEmpty()) {
            if (lib.name.startsWith("libQnn") || lib.name.startsWith("libneuron")) {
                android.system.Os.symlink(lib.path, File(dir, lib.name).path)
                linked++
            }
        }
        return dir to "LiteRT 2.4 dispatch library${if (hasPlugin) " and compiler plugin" else ""} + $linked of the app's runtime libraries"
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

    /**
     * `model`: the file NpuModels found (null: look for it here — the adb hook of test builds). `onStep(label, fraction)`
     * for the stage. Never throws (a native crash is the caller's marker's business).
     */
    @OptIn(ExperimentalApi::class)          // LiteRT-LM's benchmark(): what times the prompt and the reply
    fun run(context: Context, modelPath: String?, isActive: () -> Boolean, onStep: (String, Float) -> Unit): LlmNpuRun {
        fun notRun(line: String) = LlmNpuRun(0.0, 0.0, false, error = line.substringAfter(" · not run: ", line), lines = listOf(line))
        val head = HEAD
        val soc = NpuModels.soc()
        val vendor = vendor(soc) ?: return notRun("$head · not run: no NPU path for the chip '${soc.ifBlank { "?" }}'")
        val model = (modelPath?.let(::File) ?: NpuModels.modelFile(context))?.takeIf { it.isFile }
            ?: return notRun("$head · not run: no model file for $soc (${NpuModels.fileName(soc)})")
        val (libs, libsNote) = try { libraryDir(context, vendor) } catch (e: Exception) {
            return notRun("$head · not run: the NPU libraries couldn't be prepared: ${e.why()}").copy(model = model.name)
        }
        val backend: Backend = Backend.NPU(libs.path)
        val where = "$vendor NPU · $libsNote"
        val lines = ArrayList<String>()
        lines += "$head · ${model.name} (${model.length() / 1_000_000} MB) · $where"

        onStep("NPU · LITERT-LM · GEMMA 3 1B · SPEED", 0.1f)
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

        onStep("NPU · LITERT-LM · GEMMA 3 1B · ANSWER CHECK", 0.7f)
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
                val r = NpuLlmProbe.run(this, msg.data?.getString("model"), { true }) { label, fraction ->
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
        Handler(Looper.getMainLooper()).postDelayed({ exit() }, 30 * 60_000L)    // past the slowest step's watchdog (20 min)
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
            NpuLlmProbe.run(this, intent.getStringExtra("file"), { true }) { label, _ -> android.util.Log.e("NpuLlmProbe", "step: $label") }
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
