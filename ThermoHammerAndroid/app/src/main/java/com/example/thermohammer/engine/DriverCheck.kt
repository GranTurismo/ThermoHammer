package com.example.thermohammer.engine

import android.app.ActivityManager
import android.app.Service
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
import com.example.thermohammer.engine.ai.AiBench
import com.example.thermohammer.engine.ai.AiInputs
import com.example.thermohammer.engine.llm.LlmNative
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// DRIVER CHECK — a chip maker's driver, first in a process of its own.
//
//   The NPU and the GPU run through the chip maker's code inside the phone's
//   system, and that code can crash natively, which no try/catch stops. On a
//   Dimensity 1200, MediaTek's NeuroPilot jumped through a null pointer the first
//   time a model was compiled for it, and the Mali's Vulkan driver did the same
//   on the LLM test's first buffer: each took the whole app down.
//   So before the app itself uses a MediaTek NPU, or a GPU through Vulkan for the
//   LLM test, the same work is done once in DriverCheckService, which lives in a
//   separate process (":drivercheck"): every AI model compiled and run on the
//   NPU; a tiny LLM built and run on the GPU. If that process crashes or stops
//   answering, only it is lost: the app goes on, says why, and leaves that
//   accelerator alone. The verdict is kept for this install on this system build,
//   so a check runs once, and again after an app or a system update. Nothing here
//   is scored. Qualcomm's NPU and Adreno's OpenCL are not checked: they run
//   exactly as they always have.
// ─────────────────────────────────────────────────────────────────────────────

object DriverCheck {
    const val MSG_NPU = 1        // app → service: compile and run the AI models named in "tests" on the NPU
    const val MSG_STEP = 2       // service → app: starting on the step named in "step" (an AI model)
    const val MSG_DONE = 3       // service → app: all of it was tried and the process is still alive
    const val MSG_LLM_GPU = 4    // app → service: build and run the tiny LLM on the GPU
    const val MSG_GPU = 5        // app → service: compile and run the AI models in "tests" on the GPU with setting "setting"
    const val MSG_FAILED = 6     // service → app: the model named in "step" failed, "why"

    /** How long one step may take before the check is given up as hung. */
    const val STEP_TIMEOUT_MS = 45_000L

    sealed interface Outcome
    data object Passed : Outcome
    /** The check's process crashed while on `step` (an AI model as the stage names it; null: none was named). */
    data class Died(val step: String?) : Outcome
    /** The check's process stopped answering on `step`. */
    data class Hung(val step: String?) : Outcome
    /** The check could not be made (it didn't start, or its process was closed for another reason): nothing learned. */
    data object Unavailable : Outcome

    /**
     * Why `what` ("NPU", "GPU") is left alone after `outcome`, as the result and the report say it; null when it may
     * be used — it passed, or nothing was learned and it runs as it always has.
     */
    fun problem(outcome: Outcome, what: String): String? = when (outcome) {
        Passed, Unavailable -> null
        is Died -> "this phone's $what driver crashed in the safety check${on(outcome.step)}: the $what is not used"
        is Hung -> "this phone's $what driver did not answer in the safety check${on(outcome.step)}: the $what is not used"
    }

    private fun on(step: String?) = step?.let { " ($it)" } ?: ""

    /** What to keep of `outcome` for `key` (this install on this system build), or null when there is nothing to keep. */
    fun record(key: String, outcome: Outcome): String? = when (outcome) {
        Passed -> "$key\npassed"
        is Died -> "$key\ndied\n${outcome.step.orEmpty()}"
        is Hung -> "$key\nhung\n${outcome.step.orEmpty()}"
        Unavailable -> null
    }

    /** The verdict kept in `saved` when it was made for `key`; null when the check has to run. */
    fun cached(saved: String?, key: String): Outcome? {
        val lines = saved?.split('\n') ?: return null
        if (lines.size < 2 || lines[0] != key) return null
        val step = lines.getOrNull(2)?.ifBlank { null }
        return when (lines[1]) { "passed" -> Passed; "died" -> Died(step); "hung" -> Hung(step); else -> null }
    }

    /** ApplicationExitInfo's reasons that mean a process was closed from outside, not that it crashed: LOW_MEMORY,
     *  PERMISSION_CHANGE, USER_REQUESTED, USER_STOPPED, DEPENDENCY_DIED, PACKAGE_STATE_CHANGE, PACKAGE_UPDATED. */
    private val CLOSED_REASONS = setOf(3, 8, 10, 11, 12, 15, 16)

    /** A check whose process went away: a crash, unless Android's record of its end (`exitReason`, null: none) says it was closed. */
    fun afterDeath(step: String?, exitReason: Int?): Outcome =
        if (exitReason != null && exitReason in CLOSED_REASONS) Unavailable else Died(step)

    /** Every AI model in `tests` compiled and run once on the NPU, in the check's process; `onModel` as each starts (main thread). */
    suspend fun npu(context: Context, tests: List<AiTest>, onModel: (AiTest) -> Unit): Outcome =
        ask(context, MSG_NPU, Bundle().apply { putStringArrayList("tests", ArrayList(tests.map { it.name })) }) { name ->
            AiTest.entries.firstOrNull { it.name == name }?.also(onModel)?.model
        }

    /** The tiny LLM built and run on the GPU llama.cpp chose, in the check's process. */
    suspend fun llmGpu(context: Context): Outcome = ask(context, MSG_LLM_GPU, Bundle()) { null }

    /**
     * Every AI model in `tests` compiled and run once on the GPU with the AI tuning's setting `settingId`, in the check's
     * process. LiteRT's GPU code is fragile in the app's own process: on an Adreno, YOLOX failing to compile on OpenGL
     * left every model after it failing too (Honor 600 Pro, 2026-11-23), and a failed compile followed by an OpenCL run
     * aborted the process. So a model that fails here is never compiled in the app. The models that failed, with why,
     * go to `failed`; `onModel` as each starts (main thread).
     */
    suspend fun gpu(context: Context, settingId: String, tests: List<AiTest>, failed: MutableMap<AiTest, String>,
                    onModel: (AiTest) -> Unit): Outcome =
        ask(context, MSG_GPU, Bundle().apply {
            putString("setting", settingId); putStringArrayList("tests", ArrayList(tests.map { it.name }))
        }, onFailed = { name, why -> AiTest.entries.firstOrNull { it.name == name }?.let { failed[it] = why } }) { name ->
            AiTest.entries.firstOrNull { it.name == name }?.also(onModel)?.model
        }

    /**
     * Sends `what` to the service and waits: for its MSG_DONE, for its process to go away, or for a step to take longer
     * than `stepTimeoutMs`. `onStep` gets each step's name as it starts and returns how to call it in a verdict. A
     * caller that is cancelled just lets go of the service, which then ends its process.
     */
    private suspend fun ask(context: Context, what: Int, data: Bundle, stepTimeoutMs: Long = STEP_TIMEOUT_MS,
                            onFailed: (String, String) -> Unit = { _, _ -> },
                            onStep: (String) -> String?): Outcome = withContext(Dispatchers.Main.immediate) {
        val done = CompletableDeferred<Outcome>()
        var step: String? = null
        var watchdog: Job? = null
        fun arm() {
            watchdog?.cancel()
            watchdog = launch { delay(stepTimeoutMs); done.complete(Hung(step)) }
        }
        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                MSG_STEP -> { step = msg.data.getString("step")?.let(onStep); arm() }
                MSG_DONE -> done.complete(Passed)
                MSG_FAILED -> onFailed(msg.data.getString("step").orEmpty(), msg.data.getString("why").orEmpty())
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val asked = runCatching {
                    Messenger(binder).send(Message.obtain(null, what).apply { replyTo = replies; this.data = data })
                }
                if (asked.isFailure) done.complete(afterDeath(null, lastExitReason(context))) else arm()
            }
            override fun onServiceDisconnected(name: ComponentName) { done.complete(afterDeath(step, lastExitReason(context))) }
            override fun onBindingDied(name: ComponentName) { done.complete(afterDeath(step, lastExitReason(context))) }
            override fun onNullBinding(name: ComponentName) { done.complete(Unavailable) }
        }
        val bound = runCatching {
            context.bindService(Intent(context, DriverCheckService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            return@withContext Unavailable
        }
        arm()      // a service that never connects is a check that hung
        try {
            done.await()
        } finally {
            watchdog?.cancel()
            // letting go also keeps Android from starting a crashed service again
            runCatching { context.unbindService(connection) }
        }
    }

    /** Android's record of how the check's process last ended (ApplicationExitInfo's reason); null: none, or Android 10 and older. */
    private fun lastExitReason(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .firstOrNull { it.processName == context.packageName + DriverCheckService.PROCESS }
                ?.takeIf { System.currentTimeMillis() - it.timestamp < 60_000 }?.reason
        }.getOrNull()
    }
}

/** The check's own process: it exists only while the app is bound to it, and ends itself when the app lets go. */
class DriverCheckService : Service() {
    companion object {
        /** The process's name after the package's (AndroidManifest.xml). */
        const val PROCESS = ":drivercheck"
    }

    private val worker = HandlerThread("driver-check").apply { start() }
    private val messenger by lazy {
        Messenger(Handler(worker.looper) { msg ->
            val reply = msg.replyTo
            when (msg.what) {
                DriverCheck.MSG_NPU -> npu(reply, msg.data.getStringArrayList("tests").orEmpty())
                DriverCheck.MSG_LLM_GPU -> llmGpu()
                DriverCheck.MSG_GPU -> gpu(reply, msg.data.getString("setting"), msg.data.getStringArrayList("tests").orEmpty())
            }
            runCatching { reply?.send(Message.obtain(null, DriverCheck.MSG_DONE)) }
            true
        })
    }

    override fun onCreate() {
        super.onCreate()
        // whatever happens to the app, this process does not outstay a check
        Handler(Looper.getMainLooper()).postDelayed({ exit() }, 10 * 60_000L)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean { exit(); return false }

    override fun onDestroy() { exit() }

    private fun exit() = android.os.Process.killProcess(android.os.Process.myPid())

    private fun npu(reply: Messenger?, names: List<String>) {
        // its own dispatch folder: the app's is in use by the app
        val bench = AiBench(applicationContext, dispatchFolder = "npu-dispatch-check")
        val inputs = AiInputs(applicationContext)
        for (test in AiTest.entries.filter { it.name in names }) {
            runCatching {
                reply?.send(Message.obtain(null, DriverCheck.MSG_STEP).apply { data = Bundle().apply { putString("step", test.name) } })
            }
            // compiled, warmed up and read back as the tests do it; a model the NPU refuses throws, and that is an answer too
            runCatching { bench.run(test, AiAccel.NPU, inputs.input(test).first, 0.0, { true }) { _, _ -> } }
        }
    }

    private fun gpu(reply: Messenger?, settingId: String?, names: List<String>) {
        val setting = com.example.thermohammer.engine.ai.AiSettings.gpu().firstOrNull { it.id == settingId }
        val bench = AiBench(applicationContext, dispatchFolder = "npu-dispatch-check")
        val inputs = AiInputs(applicationContext)
        for (test in AiTest.entries.filter { it.name in names }) {
            runCatching {
                reply?.send(Message.obtain(null, DriverCheck.MSG_STEP).apply { data = Bundle().apply { putString("step", test.name) } })
            }
            // compiled, warmed up and its answer looked for, as the scored run does it
            val r = runCatching { bench.run(test, AiAccel.GPU, inputs.input(test).first, 0.0, { true }, true, setting) { _, _ -> } }
            r.exceptionOrNull()?.let { e ->
                runCatching {
                    reply?.send(Message.obtain(null, DriverCheck.MSG_FAILED).apply {
                        data = Bundle().apply { putString("step", test.name); putString("why", (e.message ?: e.javaClass.simpleName).lines().first().take(120)) }
                    })
                }
            }
        }
    }

    private fun llmGpu() {
        // a GPU that refuses the model says so (false, or a throw): an answer too. Only a crash is not.
        runCatching { if (LlmNative.loaded) LlmNative.gpuSelftest(arrayOfNulls(1)) }
    }
}
