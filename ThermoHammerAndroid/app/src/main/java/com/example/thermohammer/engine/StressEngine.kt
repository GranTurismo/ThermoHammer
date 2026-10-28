package com.example.thermohammer.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.thermohammer.engine.ux.QrBench
import com.example.thermohammer.engine.ux.QrFrames
import com.example.thermohammer.engine.ux.QrResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ── Data Models ────────────────────────────────────────────────────────────────

enum class TestDuration(val displayName: String, val seconds: Int?) {
    MINUTES_5("5 Min", 5 * 60),
    MINUTES_15("15 Min", 15 * 60),
    MINUTES_30("30 Min", 30 * 60)
}

enum class StressThreadingType(val displayName: String, val value: Int) {
    SINGLE("1 Thread", 0),
    MULTI("Multi Thread", 1)
}

enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL
}

data class StabilityPoint(val time: Float, val score: Float)

data class ThermalEvent(val time: Float, val state: ThermalState) {
    val name: String
        get() =
                when (state) {
                    ThermalState.NOMINAL -> "NOMINAL"
                    ThermalState.FAIR -> "FAIR"
                    ThermalState.SERIOUS -> "SERIOUS"
                    ThermalState.CRITICAL -> "CRITICAL"
                }
}

data class CpuCoreFreq(
        val core: Int,
        val currentKHz: Long,   // 0 if offline / unreadable
        val maxKHz: Long,       // 0 if unreadable
        val capKHz: Long = 0    // scaling_max_freq — imposed ceiling (0 = unreadable)
) {
    val currentGHz: Double get() = currentKHz / 1_000_000.0
    val maxGHz: Double     get() = maxKHz     / 1_000_000.0
    /** 0‒100 % of max; returns null if max is unknown */
    val percentOfMax: Int? get() = if (maxKHz > 0) ((currentKHz.toDouble() / maxKHz) * 100).toInt().coerceIn(0, 100) else null
    /** 0‒100 % of hardware max the kernel is currently allowed to use */
    val capPercentOfMax: Int? get() = if (maxKHz > 0 && capKHz > 0) ((capKHz.toDouble() / maxKHz) * 100).toInt().coerceIn(0, 100) else null
    val isOnline: Boolean  get() = currentKHz > 0
}

data class StressState(
        val isRunning: Boolean = false,
        val phase: RunPhase = RunPhase.IDLE,
        val elapsedSeconds: Int = 0,
        val overallStability: Float = 100f,
        val coreImpacts: List<Float> = emptyList(),
        val chartPoints: List<StabilityPoint> = emptyList(),
        val thermalEvents: List<ThermalEvent> = emptyList(),
        val currentThermalState: ThermalState = ThermalState.NOMINAL,
        val currentAttribution: Attribution = Attribution.NONE,
        val capRatioAvg: Float = 1f,
        val freqRatioAvg: Float = 1f,
        val thermalHeadroom: Float? = null,
        val wasCancelledByBackground: Boolean = false,
        val wasCompleted: Boolean = false,
        val testDuration: TestDuration = TestDuration.MINUTES_5,
        val testThreadingType: StressThreadingType = StressThreadingType.MULTI,
        val sessionId: Int? = null,
        val encryptionKey: String? = null,
        val recordedStamps: List<StampV2> = emptyList(),
        val baselineIps: Double = 0.0,
        val scorecard: Scorecard? = null,
        val validityFlags: Int = 0,
        val batteryTemp: Float = 0f,
        val cpuTemp: Float = 0f,
        val thermalSensors: List<Pair<String, Float>> = emptyList(),
        val cpuFrequencies: List<CpuCoreFreq> = emptyList(),
        val initialBatteryLevel: Int = 0,
        val initialBatteryTemp: Float = 0f,
        val finalBatteryLevel: Int = 0,
        val finalBatteryTemp: Float = 0f,
        val currentBatteryLevel: Int = 0,
        val cooldownTargetC: Float = 0f,
        val cooldownElapsedSec: Int = 0,
        val busyWait: Boolean = false,                   // cooldown also waiting for background work to finish
        val preRunIps: List<Double> = emptyList(),      // raw IPS per 250ms window — warmup+calibration
        val calibrationMarkIdx: Int = 0,                 // index in preRunIps where calibration began
        val stressMode: StressMode = StressMode.CPU,
        val gpuFps: Double = -1.0,                       // live GPU fps (−1 = channel off)
        val gpuBaselineFps: Double = 0.0,
        val gpuName: String = "",
        val gpuActive: Boolean = false,                  // GPU renderer thread running
        val preRunGpuFps: List<Double> = emptyList(),    // ghost prefix for the GPU channel
        // ── GPU benchmark run (RunKind.BENCHMARK) ──
        val runKind: RunKind = RunKind.STRESS,
        val benchTest: BenchmarkTest? = null,            // test on screen now
        val benchPlan: List<BenchmarkTest> = emptyList(), // tests this phone will run (Test 3 needs ray tracing)
        val benchProgress: Float = 0f,                   // 0..1 through that test's timeline
        val benchResult: BenchmarkResult? = null,
        val benchError: String? = null,                  // a test failed to render
        // ── UX tests (after the GPU tests) ──
        val uxTest: UxTest? = null,                      // UX test on screen now
        val uxProgress: Float = 0f,                      // 0..1 through it
        val uxDetail: String = "",                       // e.g. "CANVAS 2D · 1,240 SHAPES"
        val uxValue: Double = -1.0,                      // big number on the stage (fps or decodes/s)
        val uxUnit: String = "",
        val videoOsd: VideoOsd? = null,                  // UX 5: the player overlay
        val exportStage: ExportStage? = null,            // UX 6: the export dialog
        val qrFrames: com.example.thermohammer.engine.ux.QrFrames? = null,
        val qrFrameIndex: Int = 0,
        val qrFound: FloatArray? = null,                 // where the scanner found the code (x, y pairs)
        // ── CPU tests (first) ──
        val cpuTest: com.example.thermohammer.engine.cpu.CpuTest? = null,
        val cpuProgress: Float = 0f,
        val cpuKernel: String = "",
        val cpuRate: Double = -1.0,                      // ops/s of the kernel running now
        val cpuThreads: Int = 1,
        // ── MEM tests (after the CPU tests) ──
        val memTest: MemTest? = null,
        val memProgress: Float = 0f,                     // 0..1 through it
        val memMeasure: String = "",                     // e.g. "RANDOM READ"
        val memDetail: String = "",                      // e.g. "4 KB · 4 THREADS · DIRECT I/O"
        val memRate: Double = -1.0,
        val memUnit: String = "",
        val memAccess: com.example.thermohammer.engine.mem.MemAccess? = null,
        // ── AI tests (last) ──
        val aiTest: AiTest? = null,                      // model on screen now
        val aiAccel: AiAccel? = null,                    // where it's running
        val aiProgress: Float = 0f,                      // 0..1 through that model (all three accelerators)
        val aiRate: Double = -1.0,                       // inferences/s now
        val aiDetail: String = "",
        val aiVisual: com.example.thermohammer.engine.ai.AiVisual? = null,
        // ── LLM test (last) ──
        val llmAccel: LlmAccel? = null,                  // where the model runs now — the stage is up while set
        val llmPhase: com.example.thermohammer.engine.llm.LlmPhase = com.example.thermohammer.engine.llm.LlmPhase.BUILD,
        val llmProgress: Float = 0f,                     // 0..1 through this accelerator
        val llmRate: Double = -1.0,                      // tokens/s so far
        val llmDetail: String = "",
        val llmReply: List<Int> = emptyList(),           // the reply being written, token ids
        val llmPrompts: Int = 0                          // prompts read so far on this accelerator
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class StressEngine(private val appContext: Context) : ViewModel(), DefaultLifecycleObserver {

    companion object {
        const val WARMUP_MS = 12_000L          // JIT/governor settle — unrecorded
        const val CALIBRATION_MS = 20_000L     // boost window — basis of the baseline
        const val SAMPLE_MS = 250L             // internal sub-sample rate (4 Hz)
        const val STAMP_MS = 1_000L            // persisted/uploaded sample rate (1 Hz)
        const val WORK_CHUNK = 4_096           // counter publish granularity

        const val CAP_THROTTLE = 0.97f         // scaling_max below 97% of hw max = imposed cap
        const val FREQ_LOW = 0.92f             // cur below 92% with cap flat = governor
        const val SCHED_STARVED = 0.85f        // scheduled fraction below this = contention
        const val MIGRATION_DELTA = 0.15       // little-cluster IPS share growth = migration
        const val ONSET_RATIO = 0.90f          // throttle onset threshold
        const val ONSET_DEBOUNCE = 3           // consecutive stamps to confirm onset

        const val GATE_TEMP_C = 40f            // max CPU-adjacent temp at start
        const val GATE_HEADROOM = 0.85f        // getThermalHeadroom() from here on flags a warm start — it rises with heat, 1.0 = severe throttling
        const val GATE_BATTERY_PCT = 15        // min battery level
        const val GATE_BG_BUSY = 0.15f         // max background CPU busy fraction
        const val BG_SAMPLE_MS = 3_000L        // pre-flight background-load window

        const val COOLDOWN_TARGET_C = 40f      // wait until CPU-adjacent zones reach this
        const val QUIET_SHARE = 0.85f          // the benchmark starts once it gets ≥85% of the CPU…
        const val QUIET_STEADY = 0.05f         // …or once 3 checks in a row agree within 5 points: that's this
                                               // phone's normal (parked cores, its own UI), not passing work
        const val QUIET_POLL_MS = 3_000L       // re-measure while the phone is busy
        const val QUIET_MAX_MS = 60_000L       // then run anyway, flagged
        const val COOLDOWN_MAX_MS = 30_000L    // hard cap — flag & proceed beyond this
        const val COOLDOWN_STALL_MS = 30_000L  // plateau detection window
        const val COOLDOWN_STALL_DELTA = 0.5f  // <0.5°C improvement in window = plateaued

        private const val KEY_RT_RUNNING = "rt_running_install"
        private const val KEY_RT_CRASHED = "rt_crashed_install"
        private const val KEY_AI_RUNNING = "ai_running"          // "install:test:accel" while an AI run is in flight
        private const val KEY_AI_CRASHED = "ai_crashed"          // {"install:accel"} that took the app down
        private const val AI_SECONDS = 1.6                        // measured per model and accelerator
        private const val KEY_LLM_RUNNING = "llm_running"        // "install:accel" while an LLM run is in flight
        private const val KEY_LLM_CRASHED = "llm_crashed"        // {"install:accel"} that took the app down
        private const val LLM_NOTICE_MS = 3_000L                  // how long "not run" stays up when the test can't run
        private const val AI_SHOW_NS = 3_500_000_000L              // each model's result stays up at least this long
    }

    private val _state = MutableStateFlow(StressState())
    val state: StateFlow<StressState> = _state.asStateFlow()

    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    /** CPU cluster topology from cpufreq/related_cpus — fixed for the device. */
    val topology: ClusterTopology = StressProbes.readTopology(coreCount)
    val governor: String = StressProbes.readGovernor(coreCount)

    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    init {
        registerThermalListener()
        // Live panel telemetry — runs for the ViewModel's whole lifetime.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                updateLiveTemperatures()
                delay(1000)
            }
        }
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val mapped = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
                    PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                    PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.NOMINAL
                }
                setThermalState(mapped)
            }
            powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
            thermalListener = listener
        } catch (_: Exception) { /* listener unsupported — polling fallback remains */ }
    }

    override fun onCleared() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                try { powerManager.removeThermalStatusListener(it) } catch (_: Exception) {}
            }
        }
        super.onCleared()
    }

    private fun updateLiveTemperatures() {
        val batt = StressProbes.battery(appContext)
        val zones = StressProbes.readThermalZones()
        val cpuTemp = StressProbes.cpuAdjacentTemp(zones) ?: 0f
        val sensorPairs = zones.map { Pair(it.name, it.tempC) }
        val freqs = StressProbes.readCpuFreqs(coreCount).map { cf ->
            CpuCoreFreq(cf.core, cf.curKHz, cf.hwMaxKHz, cf.capKHz)
        }
        _state.update {
            it.copy(
                    batteryTemp = batt.tempC,
                    currentBatteryLevel = batt.level,
                    cpuTemp = cpuTemp,
                    thermalSensors = sensorPairs,
                    cpuFrequencies = freqs,
                    thermalHeadroom = StressProbes.headroom(powerManager)
            )
        }
    }

    // ── Worker controls ────────────────────────────────────────────────────────

    private val threadAlive = AtomicBoolean(false)
    private val counters: Array<AtomicLong> = Array(coreCount) { AtomicLong(0L) }
    private val workerTids = IntArray(coreCount) { -1 }
    private var activeThreadCount = 0
    private var startBrightness: Int? = null
    private var batterySaverAtStart = false

    private var sessionJob: Job? = null
    private var workerThreads: List<Thread> = emptyList()
    private val gpuBench = GpuBench(appContext.assets, appContext.cacheDir)
    private val vkBench = VkBench(appContext.assets)

    // ── Test 3 crash guard ──
    // A GPU driver bug in the ray-tracing path kills the process (native code can't be caught). A marker
    // written before Test 3 and cleared after it survives that crash; the next runs then skip Test 3 —
    // until the app is updated, which retries it.
    private val benchPrefs = appContext.getSharedPreferences("benchmark", Context.MODE_PRIVATE)
    private val installStamp: Long = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
    } catch (_: Exception) { 0L }

    /** Why GPU Test 3 won't run on this phone (no ray tracing, or it crashed here before), or null. */
    fun rayTracingSkipReason(): String? {
        if (benchPrefs.getLong(KEY_RT_RUNNING, 0L) == installStamp && installStamp != 0L) {
            benchPrefs.edit().remove(KEY_RT_RUNNING).putLong(KEY_RT_CRASHED, installStamp).commit()
        }
        if (benchPrefs.getLong(KEY_RT_CRASHED, 0L) == installStamp && installStamp != 0L)
            return "Skipped: the ray-tracing test crashed on this phone last time"
        return VkBench.rayTracingProblem
    }
    private var gpuFramesPrev = 0.0

    /**
     * Accelerators whose AI or LLM run took the app down (a native fault can't be caught): a marker left from the
     * last session ("install:…:accel") becomes a crash record, and that accelerator is skipped until the app updates.
     */
    private fun crashedAccels(runningKey: String, crashedKey: String): Set<String> {
        fun crashed() = benchPrefs.getStringSet(crashedKey, emptySet()).orEmpty()
        benchPrefs.getString(runningKey, null)?.let { running ->
            val parts = running.split(':')
            val edit = benchPrefs.edit().remove(runningKey)
            if (parts.size >= 2 && parts[0] == "$installStamp") edit.putStringSet(crashedKey, crashed() + "${parts[0]}:${parts.last()}")
            edit.commit()
        }
        return crashed().mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0] == "$installStamp" }?.get(1) }.toSet()
    }

    private fun aiCrashedAccels(): Set<AiAccel> =
        crashedAccels(KEY_AI_RUNNING, KEY_AI_CRASHED).mapNotNull { n -> AiAccel.entries.firstOrNull { it.name == n } }.toSet()

    private fun llmCrashedAccels(): Set<LlmAccel> =
        crashedAccels(KEY_LLM_RUNNING, KEY_LLM_CRASHED).mapNotNull { n -> LlmAccel.entries.firstOrNull { it.name == n } }.toSet()

    // ── Lifecycle (background cancel) ────────────────────────────────────────

    override fun onStop(owner: LifecycleOwner) {
        if (_state.value.isRunning) {
            _state.update { it.copy(wasCancelledByBackground = true) }
            stopTest()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTest(
            duration: TestDuration,
            threadingType: StressThreadingType = StressThreadingType.MULTI,
            mode: StressMode = StressMode.CPU
    ) {
        if (_state.value.isRunning) return

        activeThreadCount =
            if (!mode.usesCpu) 0
            else if (threadingType == StressThreadingType.SINGLE) 1 else coreCount
        val initialCores =
                List(coreCount) { idx ->
                    if (threadingType == StressThreadingType.SINGLE && idx > 0) 0f else 100f
                }
        val startBatt = StressProbes.battery(appContext)
        startBrightness = StressProbes.screenBrightness(appContext)
        batterySaverAtStart = isBatterySaverOn()

        _state.update {
            it.copy(
                    isRunning = true,
                    phase = RunPhase.COOLDOWN,
                    cooldownTargetC = COOLDOWN_TARGET_C,
                    cooldownElapsedSec = 0,
                    preRunIps = emptyList(),
                    calibrationMarkIdx = 0,
                    stressMode = mode,
                    runKind = RunKind.STRESS,
                    cpuTest = null,
                    memTest = null,
                    uxTest = null,
                    aiTest = null,
                    aiVisual = null,
                    llmAccel = null,
                    qrFrames = null,
                    benchTest = null,
                    benchResult = null,
                    benchError = null,
                    gpuFps = -1.0,
                    gpuBaselineFps = 0.0,
                    gpuName = "",
                    preRunGpuFps = emptyList(),
                    elapsedSeconds = 0,
                    overallStability = 100f,
                    coreImpacts = initialCores,
                    chartPoints = emptyList(),
                    thermalEvents = emptyList(),
                    wasCancelledByBackground = false,
                    wasCompleted = false,
                    testDuration = duration,
                    testThreadingType = threadingType,
                    recordedStamps = emptyList(),
                    scorecard = null,
                    validityFlags = 0,
                    initialBatteryLevel = startBatt.level,
                    initialBatteryTemp = startBatt.tempC,
                    finalBatteryLevel = startBatt.level,
                    finalBatteryTemp = startBatt.tempC,
                    currentBatteryLevel = startBatt.level,
                    batteryTemp = startBatt.tempC
            )
        }

        addThermalEvent(_state.value.currentThermalState, 0f)

        for (i in 0 until coreCount) {
            counters[i].set(0L)
            workerTids[i] = -1
        }

        threadAlive.set(true)
        // Workers are NOT spawned here — runSession spawns them after COOLDOWN
        // so the device can actually shed heat (zero load while waiting).
        sessionJob = viewModelScope.launch(Dispatchers.IO) { runSession() }
    }

    /** Spawn the N worker threads — called once COOLDOWN completes. */
    private fun startWorkers() {
        workerThreads =
                (0 until activeThreadCount).map { idx ->
                    Thread {
                        workerTids[idx] = android.os.Process.myTid()
                        var v1 = -6148914691236517206L // 0xAAAAAAAAAAAAAAAA as signed Long
                        var v2 = 6148914691236517205L  // 0x5555555555555555 as signed Long
                        var v3 = 3689348814741910323L  // 0x3333333333333333 as signed Long
                        var v4 = 8608480567731124087L  // 0x7777777777777777 as signed Long

                        var f1 = 1.0000001
                        var f2 = 2.0000002
                        var f3 = 3.0000003
                        var f4 = 4.0000004

                        val l1Cache = LongArray(4096) { it.toLong() }
                        var cacheIdx = 0

                        while (threadAlive.get()) {
                            var i = 0
                            while (i < WORK_CHUNK) {
                                v1 = (v1 xor (v2 + 7L)) * 3L
                                f1 = f1 * 1.0000001 + 0.0000001

                                v2 = (v2 xor (v3 + 13L)) * 5L
                                f2 = f2 * 1.0000002 + 0.0000002

                                v3 = (v3 xor (v4 + 17L)) * 7L
                                f3 = f3 * 1.0000003 + 0.0000003

                                v4 = (v4 xor (v1 + 19L)) * 11L
                                f4 = f4 * 1.0000004 + 0.0000004

                                l1Cache[cacheIdx] = l1Cache[cacheIdx] xor v1
                                cacheIdx = (cacheIdx + 1) and 4095

                                i++
                            }
                            counters[idx].addAndGet(WORK_CHUNK.toLong())
                        }
                        // Prevent optimizer from eliminating dead code
                        if (v1 + v2 + v3 + v4 + f1.toLong() == 0L) println("noop: $v1 $f1")
                    }
                            .also {
                                it.priority = Thread.NORM_PRIORITY
                                it.start()
                            }
                }
    }

    /**
     * GPU benchmark: cooldown, then each BenchmarkTest rendered for its fixed timeline.
     * No CPU workers — the score is pure GPU throughput (fps → points → geometric mean).
     */
    /** Debug builds only (MainActivity's `benchFrom` extra): start at that UX test, skipping what comes before. */
    private var benchFrom: String? = null

    fun startBenchmark(from: String? = null) {
        if (_state.value.isRunning) return
        benchFrom = from
        activeThreadCount = 0
        val startBatt = StressProbes.battery(appContext)
        startBrightness = StressProbes.screenBrightness(appContext)
        batterySaverAtStart = isBatterySaverOn()
        _state.update {
            it.copy(
                    isRunning = true,
                    runKind = RunKind.BENCHMARK,
                    cpuTest = null,
                    memTest = null,
                    uxTest = null,
                    aiTest = null,
                    aiVisual = null,
                    llmAccel = null,
                    qrFrames = null,
                    qrFound = null,
                    phase = RunPhase.COOLDOWN,
                    cooldownTargetC = COOLDOWN_TARGET_C,
                    cooldownElapsedSec = 0,
                    stressMode = StressMode.GPU,
                    benchTest = null,
                    benchProgress = 0f,
                    benchResult = null,
                    benchError = null,
                    gpuFps = -1.0,
                    gpuBaselineFps = 0.0,
                    elapsedSeconds = 0,
                    wasCancelledByBackground = false,
                    wasCompleted = false,
                    validityFlags = 0,
                    coreImpacts = emptyList(),
                    initialBatteryLevel = startBatt.level,
                    initialBatteryTemp = startBatt.tempC,
                    finalBatteryLevel = startBatt.level,
                    finalBatteryTemp = startBatt.tempC
            )
        }
        threadAlive.set(true)
        sessionJob = viewModelScope.launch(Dispatchers.IO) { runBenchmark() }
    }

    private suspend fun runBenchmark() {
        var flags = 0
        val from = benchFrom
        // Getting ready: cool, and nothing else eating the CPU. If the phone was busy, that work
        // may have warmed it again, so the temperature is checked once more. A phone that doesn't get down to the
        // target (many idle at 45–50 °C) isn't flagged: the wait is a courtesy, not a condition.
        val cooled = from != null || coolDown()
        val quiet = if (from != null) QuietCheck(false, 1f, 0) else waitUntilQuiet()
        if (cooled && quiet.waitedSec >= 20) coolDown()   // it had cooled; the busy spell may have warmed it
        if (!quiet.quiet) flags = flags or ValidityFlag.INTERFERENCE   // a partial debug run is never a verified one
        if (!_state.value.isRunning) return
        if (batterySaverAtStart) flags = flags or ValidityFlag.ENV_CAPPED
        val rtProblem = rayTracingSkipReason()
        val plan = BenchmarkTest.entries.filter { !it.rayTracing || rtProblem == null }
        _state.update { it.copy(benchPlan = plan) }
        val cpu = if (from == null) runCpu() ?: return else null
        val mem = if (from == null) runMem() ?: return else null
        val results = ArrayList<BenchmarkTestResult>()
        val runStartNs = System.nanoTime()
        for (test in BenchmarkTest.entries) {
            if (!_state.value.isRunning) return
            if (from != null) { results += BenchmarkTestResult.unsupported(test); continue }
            if (test !in plan) {                       // no ray tracing on this phone: the test scores 0
                results += BenchmarkTestResult.unsupported(test)
                continue
            }
            val startTemp = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones())
            _state.update { it.copy(phase = RunPhase.MEASURED, cpuTest = null, memTest = null, benchTest = test, benchProgress = 0f, gpuFps = -1.0) }
            if (test.rayTracing) benchPrefs.edit().putLong(KEY_RT_RUNNING, installStamp).commit()
            val runner: GpuRunner = if (test.rayTracing) vkBench.also { it.start(test.seconds.toFloat()) }
                else gpuBench.also { it.start(test.scene, test.seconds.toFloat()) }
            gpuFramesPrev = 0.0
            var prevNs = System.nanoTime()
            while (_state.value.isRunning && !runner.finished && runner.glError == null) {
                delay(SAMPLE_MS)
                val now = System.nanoTime()
                val fps = gpuFpsDelta(now - prevNs, runner)
                prevNs = now
                if (StressProbes.battery(appContext).charging) flags = flags or ValidityFlag.POWER_EVENT
                _state.update {
                    it.copy(
                        gpuActive = true,
                        gpuName = runner.gpuName.ifEmpty { it.gpuName },
                        gpuFps = fps,
                        benchProgress = (runner.sceneSeconds / test.seconds).coerceIn(0f, 1f),
                        elapsedSeconds = ((now - runStartNs) / 1_000_000_000L).toInt()
                    )
                }
            }
            runner.stop()
            if (test.rayTracing) benchPrefs.edit().remove(KEY_RT_RUNNING).apply()
            if (!_state.value.isRunning) return
            val error = runner.glError
            val (starts, durs, count) = runner.frameLog()
            val summary = if (error == null) BenchmarkScore.summarize(test, starts, durs, count, startTemp,
                StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones())) else null
            if (summary == null) {
                _state.update { it.copy(benchError = "${test.title}: ${error ?: "too few frames rendered"}") }
                stopTest()
                return
            }
            results += summary
        }
        val gpuScore = BenchmarkScore.overall(results)
        val gpuName = gpuBench.gpuName.ifEmpty { vkBench.gpuName }
        val ux = runUx(from) ?: return
        val ai = runAi() ?: return
        val llm = runLlm() ?: return
        val result = BenchmarkResult(
            tests = results,
            gpuScore = gpuScore,
            gpuName = gpuName,
            validityFlags = flags,
            rayTracingProblem = rtProblem,
            ux = ux,
            cpu = cpu,
            ai = ai,
            mem = mem,
            llm = llm,
            startCpuShare = quiet.share,
            quietWaitSec = quiet.waitedSec
        )
        _state.update {
            it.copy(benchResult = result, validityFlags = flags, wasCompleted = true, benchProgress = 1f,
                cpuTest = null, memTest = null, uxTest = null, qrFrames = null, qrFound = null, aiTest = null, aiVisual = null,
                llmAccel = null, llmReply = emptyList())
        }
        stopTest()
    }

    // ── CPU tests ─────────────────────────────────────────────────────────────

    /** Math, JSON & XML, compression & crypto, multi-core — on the stage, before the GPU tests. Null if stopped. */
    private suspend fun runCpu(): CpuResult? {
        val bench = com.example.thermohammer.engine.cpu.CpuBench()
        val runs = ArrayList<com.example.thermohammer.engine.cpu.CpuTestRun>()
        for (test in com.example.thermohammer.engine.cpu.CpuTest.entries) {
            if (!_state.value.isRunning) return null
            _state.update {
                it.copy(phase = RunPhase.MEASURED, cpuTest = test, cpuProgress = 0f, cpuKernel = test.labels.first(),
                    cpuRate = -1.0, cpuThreads = 1, gpuActive = true)
            }
            val run = try {
                withContext(Dispatchers.Default) {
                    bench.run(test, { _state.value.isRunning }) { p ->
                        _state.update { it.copy(cpuProgress = p.fraction, cpuKernel = p.kernel, cpuRate = p.rate, cpuThreads = p.threads) }
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(benchError = "CPU ${test.title}: ${e.message ?: e.javaClass.simpleName}") }
                stopTest()
                return null
            }
            if (!_state.value.isRunning) return null
            runs += run
        }
        return CpuResult(runs)
    }

    // ── MEM tests ─────────────────────────────────────────────────────────────

    /** RAM, storage and database — after the CPU tests, while the phone is still cool. Null if stopped. */
    private suspend fun runMem(): MemResult? {
        val bench = com.example.thermohammer.engine.mem.MemBench(appContext)
        val active = { _state.value.isRunning }
        val report = { p: com.example.thermohammer.engine.mem.MemProgress ->
            _state.update {
                it.copy(memProgress = p.fraction, memMeasure = p.measure, memDetail = p.detail, memRate = p.rate,
                    memUnit = p.unit, memAccess = p.access)
            }
        }
        var ram: RamResult? = null
        var storage: StorageResult? = null
        var db: DbResult? = null
        for (test in MemTest.entries) {
            if (!_state.value.isRunning) return null
            _state.update {
                it.copy(phase = RunPhase.MEASURED, cpuTest = null, memTest = test, memProgress = 0f, memMeasure = test.labels.first(),
                    memDetail = "", memRate = -1.0, memUnit = "", memAccess = null, gpuActive = true)
            }
            try {
                withContext(Dispatchers.Default) {
                    when (test) {
                        MemTest.RAM -> ram = bench.ram(active, report)
                        MemTest.STORAGE -> storage = bench.storage(active, report)
                        MemTest.DATABASE -> db = bench.database(active, report)
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(benchError = "MEM ${test.title}: ${e.message ?: e.javaClass.simpleName}") }
                stopTest()
                return null
            }
        }
        if (!_state.value.isRunning) return null
        return MemResult(ram ?: return null, storage ?: return null, db ?: return null)
    }

    // ── UX tests ──────────────────────────────────────────────────────────────

    /** The stage's WebView reports here; each browser test parses its own JSON. */
    private var webResult: CompletableDeferred<Result<String>>? = null

    /**
     * From the stage's WebView (JavaBridge thread). Web browsing reports each ramping scene; the
     * scrolling and gallery pages send their HUD line as `scene` and the big number as `value`.
     */
    fun webProgress(scene: String, fraction: Double, complexity: Int, value: Double, index: Int) {
        when (_state.value.uxTest) {
            UxTest.WEB -> {
                val unit = when (index) { 0 -> "CARDS"; 1 -> "SHAPES"; 2 -> "TILES"; 3 -> "PATHS"; else -> "OPS" }
                _state.update {
                    it.copy(uxProgress = ((index + fraction.toFloat()) / 5f).coerceIn(0f, 1f),
                        uxDetail = "${scene.uppercase()} · %,d $unit".format(complexity),
                        uxValue = if (value > 0) value else -1.0, uxUnit = if (value > 0) "FPS" else "")
                }
            }
            UxTest.SCROLL, UxTest.GALLERY -> _state.update {
                it.copy(uxProgress = fraction.toFloat().coerceIn(0f, 1f), uxDetail = scene.uppercase(),
                    uxValue = if (value > 0) value else -1.0, uxUnit = if (it.uxTest == UxTest.SCROLL) "PX/S" else "MP/S")
            }
            else -> {}
        }
    }

    /** From the stage's WebView: the page's results as JSON. */
    fun webDone(json: String) {
        webResult?.complete(Result.success(json))
    }

    fun webFailed(message: String) {
        webResult?.complete(Result.failure(IllegalStateException(message)))
    }

    /** UX Tests 1–6. Null if the run was stopped. */
    private suspend fun runUx(from: String? = null): UxResult? {
        val errors = ArrayList<String>()
        fun <T> Result<T>.noted(test: UxTest): T? = getOrElse { errors += "${test.title.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${it.message}"; null }
        val r2 = BenchmarkScore::round2
        val first = when (from) { "video" -> UxTest.VIDEO; "export" -> UxTest.EXPORT; else -> UxTest.WEB }   // debug starts

        val web = if (first > UxTest.WEB) null else (runWebPage(UxTest.WEB) ?: return null).mapCatching { o ->
            WebResult(r2(o.getDouble("layout")), r2(o.getDouble("canvas")), r2(o.getDouble("css")),
                r2(o.getDouble("svg")), r2(o.getDouble("js")))
        }.noted(UxTest.WEB)
        val scroll = if (first > UxTest.WEB) null else (runWebPage(UxTest.SCROLL) ?: return null).mapCatching { o ->
            ScrollResult(r2(o.getDouble("speed")), r2(o.getDouble("dropped")))
        }.noted(UxTest.SCROLL)
        val gallery = if (first > UxTest.WEB) null else (runWebPage(UxTest.GALLERY) ?: return null).mapCatching { o ->
            GalleryResult(r2(o.getDouble("jpeg")), r2(o.getDouble("webp")), r2(o.getDouble("avif")))
        }.noted(UxTest.GALLERY)
        val photo = if (first > UxTest.WEB) 0.0 else (runPhotoEdit() ?: return null).noted(UxTest.PHOTO) ?: 0.0
        val video = if (first > UxTest.VIDEO) VideoResult(emptyList()) else runVideo() ?: return null
        val export = runExport() ?: return null
        val qr = runQr() ?: return null
        return UxResult(
            web = web, scroll = scroll, gallery = gallery, photoMps = photo, video = video, export = export, qr = qr,
            webPoints = web?.let { UxScore.webPoints(it) } ?: 0,
            scrollPoints = scroll?.let { UxScore.scrollPoints(it.speed) } ?: 0,
            galleryPoints = gallery?.let { UxScore.galleryPoints(it) } ?: 0,
            photoPoints = UxScore.photoPoints(photo),
            videoPoints = UxScore.videoPoints(video),
            exportPoints = UxScore.exportPoints(export),
            qrPoints = UxScore.qrPoints(qr.zxingRate),
            error = errors.joinToString("; ").ifEmpty { null }
        )
    }

    /** Shows `test`'s page on the stage and waits for its results. Null if the run was stopped. */
    private suspend fun runWebPage(test: UxTest): Result<org.json.JSONObject>? {
        val deferred = CompletableDeferred<Result<String>>()
        webResult = deferred
        _state.update {
            it.copy(benchTest = null, uxTest = test, uxProgress = 0f, uxDetail = "LOADING", uxValue = -1.0,
                gpuFps = -1.0, gpuActive = true)
        }
        val outcome = withTimeoutOrNull(test.seconds * 4_000L + 60_000L) { deferred.await() }
            ?: Result.failure(IllegalStateException("timed out"))
        webResult = null
        if (!_state.value.isRunning) return null
        return outcome.mapCatching { org.json.JSONObject(it) }
    }

    /** UX Test 4 on the GPU: megapixels through the whole editing chain per second. Null if stopped. */
    private suspend fun runPhotoEdit(): Result<Double>? {
        val test = UxTest.PHOTO
        _state.update { it.copy(uxTest = test, uxProgress = 0f, uxDetail = "LOADING THE 12 MP PHOTO", uxValue = -1.0, uxUnit = "MP/S") }
        gpuBench.start(GpuSceneId.PHOTO_EDIT, test.seconds.toFloat())
        gpuFramesPrev = 0.0
        var prevNs = System.nanoTime()
        while (_state.value.isRunning && !gpuBench.finished && gpuBench.glError == null) {
            delay(SAMPLE_MS)
            val now = System.nanoTime()
            val fps = gpuFpsDelta(now - prevNs, gpuBench)
            prevNs = now
            val mp = (gpuBench.activeScene as? com.example.thermohammer.engine.gpu.PhotoEditScene)?.megapixels ?: 0.0
            _state.update {
                it.copy(uxProgress = (gpuBench.sceneSeconds / test.seconds).coerceIn(0f, 1f),
                    uxDetail = "LENS · BLUR · BOKEH · SHARPEN · GRADE · %.0f MP".format(mp),
                    uxValue = if (fps > 0 && mp > 0) fps * mp else -1.0, gpuName = gpuBench.gpuName.ifEmpty { it.gpuName })
            }
        }
        gpuBench.stop()
        if (!_state.value.isRunning) return null
        gpuBench.glError?.let { return Result.failure(IllegalStateException(it)) }
        val mp = (gpuBench.activeScene as? com.example.thermohammer.engine.gpu.PhotoEditScene)?.megapixels ?: 0.0
        val (starts, durs, count) = gpuBench.frameLog()
        val idx = (0 until count).filter { starts[it] >= BenchmarkScore.WARMUP_S }
        if (idx.size < 3 || mp <= 0) return Result.failure(IllegalStateException("too few frames"))
        return Result.success(BenchmarkScore.round2(idx.size * 1e9 / idx.sumOf { durs[it] } * mp))
    }

    /** UX Test 5: each clip's decode rate (0 where the phone can't). Null if stopped. */
    private suspend fun runVideo(): VideoResult? {
        _state.update { it.copy(uxTest = UxTest.VIDEO, uxProgress = 0f, uxDetail = "OPENING DECODERS", uxValue = -1.0, uxUnit = "FPS",
            videoOsd = null, gpuActive = true) }
        gpuBench.start(GpuSceneId.VIDEO, 120f)                // the scene ends itself after the last clip
        while (_state.value.isRunning && !gpuBench.finished && gpuBench.glError == null) {
            delay(SAMPLE_MS)
            val scene = gpuBench.activeScene as? com.example.thermohammer.engine.gpu.VideoPlaybackScene ?: continue
            val c = scene.current
            val skipped = c != null && scene.results.any { it.clip == c }
            _state.update {
                it.copy(uxProgress = scene.progress,
                    uxDetail = when {
                        c == null -> "OPENING DECODERS"
                        skipped -> "${c.label} · NOT SUPPORTED · 0 POINTS"
                        else -> "${c.label} · ${if (scene.currentHardware) "HARDWARE" else "SOFTWARE"} · ${scene.currentDecoder ?: ""}"
                    },
                    uxValue = if (!skipped && scene.currentFps > 0) scene.currentFps else -1.0,
                    videoOsd = if (c == null || skipped) null else VideoOsd("${c.codec.label} · ${c.width}×${c.height} · ${c.fps} FPS",
                        c.mbps, scene.positionS, scene.durationS, scene.currentFps / c.fps))
            }
        }
        gpuBench.stop()
        _state.update { it.copy(videoOsd = null) }
        if (!_state.value.isRunning) return null
        val got = (gpuBench.activeScene as? com.example.thermohammer.engine.gpu.VideoPlaybackScene)?.results?.toList().orEmpty()
        val why = gpuBench.glError ?: "did not run"
        return VideoResult(VideoClip.entries.map { c ->
            got.firstOrNull { it.clip == c }?.let { it.copy(fps = BenchmarkScore.round2(it.fps)) }
                ?: VideoCodecResult(c, 0.0, null, false, why)
        })
    }

    /** UX Test 6: each export's encode rate and quality (0 where the phone has no such encoder). Null if stopped. */
    private suspend fun runExport(): ExportResult? {
        _state.update { it.copy(uxTest = UxTest.EXPORT, uxProgress = 0f, uxDetail = "OPENING ENCODERS", uxValue = -1.0, uxUnit = "FPS",
            videoOsd = null, exportStage = null, gpuActive = true) }
        gpuBench.start(GpuSceneId.EXPORT, 120f)               // the scene ends itself after the last encoder
        while (_state.value.isRunning && !gpuBench.finished && gpuBench.glError == null) {
            delay(SAMPLE_MS)
            val scene = gpuBench.activeScene as? com.example.thermohammer.engine.gpu.VideoExportScene ?: continue
            val j = scene.current
            val done = scene.results.toList()
            val skipped = j != null && done.any { it.job == j }
            val step = when {
                skipped -> done.last { r -> r.job == j }.error?.uppercase() ?: "NOT SUPPORTED"
                scene.stage == com.example.thermohammer.engine.gpu.VideoExportScene.Stage.FINISHING -> "FINISHING THE FILE"
                scene.stage == com.example.thermohammer.engine.gpu.VideoExportScene.Stage.CHECKING -> "CHECKING QUALITY"
                else -> "ENCODING"
            }
            val queue = ExportJob.entries.map { e ->
                val r = done.firstOrNull { it.job == e }
                when {
                    r != null && r.fps > 0 -> ExportStage.Item(e, ExportStage.State.DONE, r.fps, r.psnr)
                    r != null -> ExportStage.Item(e, ExportStage.State.SKIPPED, note = r.error)
                    e == j -> ExportStage.Item(e, ExportStage.State.ACTIVE)
                    else -> ExportStage.Item(e, ExportStage.State.PENDING)
                }
            }
            _state.update {
                it.copy(uxProgress = scene.progress,
                    uxDetail = when {
                        j == null -> "OPENING ENCODERS"
                        skipped -> "${j.label} ${j.res} · $step · 0 POINTS"
                        else -> "${j.label} ${j.res} · ${j.mbps} MBPS · ${if (scene.currentHardware) "HARDWARE" else "SOFTWARE"} · ${scene.currentEncoder ?: ""}"
                    },
                    uxValue = if (!skipped && scene.currentFps > 0) scene.currentFps else -1.0,
                    exportStage = j?.let { e ->
                        ExportStage(e, scene.currentEncoder, scene.currentHardware, if (skipped) 0 else scene.framesDone,
                            scene.elapsedS.toDouble(), if (skipped) 0.0 else scene.currentFps, step, queue)
                    })
            }
        }
        gpuBench.stop()
        _state.update { it.copy(videoOsd = null, exportStage = null) }
        if (!_state.value.isRunning) return null
        val got = (gpuBench.activeScene as? com.example.thermohammer.engine.gpu.VideoExportScene)?.results?.toList().orEmpty()
        val why = gpuBench.glError ?: "did not run"
        return ExportResult(ExportJob.entries.map { j ->
            got.firstOrNull { it.job == j }?.let { it.copy(fps = BenchmarkScore.round2(it.fps), psnr = BenchmarkScore.round2(it.psnr)) }
                ?: ExportJobResult(j, 0.0, 0.0, null, false, why)
        })
    }

    /** UX Test 6: ZXing on 32 camera-like frames (scored), then ML Kit (reported). Null if stopped. */
    private suspend fun runQr(): QrResult? {
        _state.update { it.copy(uxTest = UxTest.QR, uxProgress = 0f, uxDetail = "PREPARING FRAMES", uxValue = -1.0) }
        val frames = withContext(Dispatchers.Default) { QrFrames() }
        val qr = frames.use {
            if (!_state.value.isRunning) return null
            _state.update { it.copy(qrFrames = frames) }
            withContext(Dispatchers.Default) {
                QrBench(frames).run(zxingSeconds = 14.0, mlkitSeconds = 8.0, isActive = { _state.value.isRunning }) { p ->
                    val overall = if (p.phase == "ZXING") p.fraction * 0.62f else 0.62f + p.fraction * 0.38f
                    _state.update {
                        it.copy(uxProgress = overall, uxDetail = p.phase + if (p.phase == "ML KIT") " · NOT SCORED" else "",
                            uxValue = p.rate, uxUnit = "SCANS/S", qrFrameIndex = p.frame, qrFound = p.points)
                    }
                }
            }
        }
        if (!_state.value.isRunning) return null
        return qr
    }

    // ── AI tests ──────────────────────────────────────────────────────────────

    /** Each model on the CPU, the GPU and the NPU, with its result on the stage. Null if the run was stopped. */
    private suspend fun runAi(): AiResult? = withContext(Dispatchers.Default) {
        _state.update { it.copy(uxTest = null, qrFrames = null, qrFound = null, aiTest = AiTest.CLASSIFY, aiAccel = null,
            aiProgress = 0f, aiRate = -1.0, aiDetail = "LOADING MODELS", aiVisual = null) }
        val bench = com.example.thermohammer.engine.ai.AiBench(appContext)
        val inputs = com.example.thermohammer.engine.ai.AiInputs(appContext)
        val crashed = aiCrashedAccels()
        val available = bench.available()
        val npuProblem = bench.npuProblem()
        var npuBurst = true          // BURST until it fails once: then QNN's default performance mode
        var npuRetried = false       // the default-clocks retry is tried once, not on every model
        var npuLog: String? = null   // QNN's own words, the first time the NPU failed
        val runs = ArrayList<AiRun>()
        for (test in AiTest.entries) {
            if (!_state.value.isRunning) return@withContext null
            val (input, picture) = inputs.input(test)
            _state.update { it.copy(aiTest = test, aiAccel = null, aiProgress = 0f, aiRate = -1.0,
                aiDetail = test.model.uppercase(), aiVisual = com.example.thermohammer.engine.ai.AiVisual(test, picture)) }
            var decodedAt = 0L
            for ((k, accel) in AiAccel.entries.withIndex()) {
                if (!_state.value.isRunning) return@withContext null
                val skip = when {
                    accel in crashed -> "crashed the app here last time"
                    accel == AiAccel.GPU && AiAccel.GPU !in available -> "no GPU accelerator"
                    accel == AiAccel.NPU -> npuProblem
                    else -> null
                }
                if (skip != null) { runs += AiRun(test, accel, 0.0, skip); continue }
                val runtime = if (accel == AiAccel.NPU) bench.npuRuntime() else null
                _state.update { it.copy(aiAccel = accel, aiRate = -1.0, aiProgress = k / 3f,
                    aiDetail = "${accel.label}${runtime?.let { r -> " · $r" } ?: ""} · INT8 · ${test.model.uppercase()} · COMPILING") }
                benchPrefs.edit().putString(KEY_AI_RUNNING, "$installStamp:${test.name}:${accel.name}").commit()
                fun attempt(burst: Boolean) = bench.run(test, accel, input, AI_SECONDS, { _state.value.isRunning }, burst) { f, ips ->
                    _state.update { it.copy(aiProgress = (k + f) / 3f, aiRate = ips,
                        aiDetail = "${accel.label}${runtime?.let { r -> " · $r" } ?: ""} · INT8 · ${test.model.uppercase()}") }
                }
                val startedMs = System.currentTimeMillis()
                val measured = try {
                    if (accel != AiAccel.NPU) attempt(true)
                    else try {
                        attempt(npuBurst)
                    } catch (e: Exception) {
                        // what QNN said, for the report; then once, the NPU without BURST (QNN's default clocks)
                        if (npuLog == null) npuLog = bench.npuLog(startedMs - 1000)
                        if (!npuBurst || npuRetried || !_state.value.isRunning) throw e
                        npuRetried = true
                        attempt(false).also { npuBurst = false }
                    }
                } catch (e: Exception) {
                    runs += AiRun(test, accel, 0.0, e.message ?: e.javaClass.simpleName)
                    null
                } finally {
                    benchPrefs.edit().remove(KEY_AI_RUNNING).commit()
                }
                if (measured == null || !_state.value.isRunning) continue
                runs += AiRun(test, accel, BenchmarkScore.round2(measured.ips))
                if (decodedAt == 0L) {
                    decodedAt = System.nanoTime()
                    val v = runCatching { inputs.visual(test, picture, measured.outputs) }.getOrNull()
                    if (v != null) _state.update { it.copy(aiVisual = v) }
                }
            }
            // where accelerators were skipped the model would flash by: leave its result up long enough to read
            if (decodedAt != 0L) {
                val left = AI_SHOW_NS - (System.nanoTime() - decodedAt)
                if (left > 0) { _state.update { it.copy(aiProgress = 1f) }; delay(left / 1_000_000) }
            }
        }
        AiResult(fallbackChecked(runs), bench.npuRuntime().takeIf { npuProblem == null }, npuProblem,
            npuFiles = if (bench.npuRuntime() == "Qualcomm QNN") bench.npuFiles() else null,
            npuLog = npuLog, npuBurst = npuBurst)
    }

    /**
     * An "NPU" no faster than the CPU across the models means LiteRT ran it on the CPU after all (its
     * fallback is silent): those runs score 0 instead of passing for an NPU.
     */
    private fun fallbackChecked(runs: List<AiRun>): List<AiRun> {
        fun ips(t: AiTest, a: AiAccel) = runs.firstOrNull { it.test == t && it.accel == a }?.ips ?: 0.0
        val ratios = AiTest.entries.mapNotNull { t -> ips(t, AiAccel.CPU).takeIf { it > 0 }?.let { c -> ips(t, AiAccel.NPU) / c }?.takeIf { it > 0 } }
        if (ratios.isEmpty()) return runs
        val speedup = kotlin.math.exp(ratios.sumOf { kotlin.math.ln(it) } / ratios.size)
        if (speedup >= 1.2) return runs
        return runs.map { if (it.accel == AiAccel.NPU && it.ips > 0) it.copy(ips = 0.0,
            error = "no faster than the CPU (%.2f×): LiteRT fell back to it".format(speedup)) else it }
    }

    // ── LLM test ──────────────────────────────────────────────────────────────

    /** The 3B model on the CPU, then the GPU, with the reply it writes on the stage. Null if the run was stopped. */
    private suspend fun runLlm(): LlmResult? = withContext(Dispatchers.Default) {
        _state.update { it.copy(aiTest = null, aiVisual = null, llmAccel = LlmAccel.CPU, llmPhase = com.example.thermohammer.engine.llm.LlmPhase.BUILD,
            llmProgress = 0f, llmRate = -1.0, llmDetail = "PREPARING", llmReply = emptyList(), llmPrompts = 0) }
        val bench = com.example.thermohammer.engine.llm.LlmBench(appContext)
        val free = bench.freeBytes()
        val freeGb = BenchmarkScore.round2(free / 1e9)
        val largest = bench.largestModel(free)
        bench.problem()?.let { problem ->
            // nothing can run: say why for a moment, every accelerator scores 0
            _state.update { it.copy(llmDetail = "NOT RUN · ${problem.uppercase()}") }
            delay(LLM_NOTICE_MS)
            return@withContext LlmResult(LlmAccel.entries.map { LlmRun(it, 0.0, 0.0, error = problem) }, null, freeGb, largest, problem)
        }
        val crashed = llmCrashedAccels()
        val gpuProblem = bench.gpuProblem()
        val runs = ArrayList<LlmRun>()
        var threads = 4
        for (accel in LlmAccel.entries) {
            if (!_state.value.isRunning) return@withContext null
            val skip = when {
                accel in crashed -> "crashed the app here last time"
                accel == LlmAccel.GPU -> gpuProblem
                else -> null
            }
            if (skip != null) { runs += LlmRun(accel, 0.0, 0.0, error = skip); continue }
            _state.update { it.copy(llmAccel = accel, llmProgress = 0f, llmRate = -1.0, llmReply = emptyList(), llmPrompts = 0) }
            val startTemp = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones())
            benchPrefs.edit().putString(KEY_LLM_RUNNING, "$installStamp:${accel.name}").commit()
            val run: LlmRun? = try {
                bench.run(accel, threads, { _state.value.isRunning }) { p ->
                    _state.update { it.copy(llmPhase = p.phase, llmProgress = p.fraction, llmRate = p.rate, llmDetail = p.detail,
                        llmReply = p.reply, llmPrompts = p.prompts) }
                }
            } catch (e: Exception) {
                LlmRun(accel, 0.0, 0.0, error = e.message ?: e.javaClass.simpleName)
            } finally {
                benchPrefs.edit().remove(KEY_LLM_RUNNING).commit()
            }
            if (run == null) return@withContext null
            if (run.threads > 0) threads = run.threads
            runs += run.copy(startTempC = startTemp)
        }
        val gpuRan = runs.any { it.accel == LlmAccel.GPU && it.decode > 0 }
        LlmResult(runs, bench.gpuName().takeIf { gpuRan && it.isNotEmpty() }, freeGb, largest)
    }

    /** UI attaches/detaches the stage SurfaceView — forwarded to the GL thread. */
    fun gpuAttachSurface(surface: android.view.Surface) {
        gpuBench.attachDisplay(surface)
        vkBench.attachDisplay(surface)
    }
    fun gpuDetachSurface() {
        gpuBench.detachDisplay()
        vkBench.detachDisplay()
    }

    fun stopTest() {
        if (!_state.value.isRunning) return
        threadAlive.set(false)
        gpuBench.stop()
        vkBench.stop()
        // a stopped run is not a crash: only a process killed inside Test 3, an AI or an LLM run leaves a marker behind
        benchPrefs.edit().remove(KEY_RT_RUNNING).remove(KEY_AI_RUNNING).remove(KEY_LLM_RUNNING).apply()
        _state.update { it.copy(gpuActive = false) }
        sessionJob?.cancel()
        sessionJob = null
        val endLevel = _state.value.currentBatteryLevel
        val endTemp = _state.value.batteryTemp
        _state.update {
            it.copy(
                    isRunning = false,
                    phase = RunPhase.IDLE,
                    finalBatteryLevel = endLevel,
                    finalBatteryTemp = endTemp
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            workerThreads.forEach {
                try { it.interrupt() } catch (_: Exception) {}
            }
        }
    }

    fun setSession(id: Int, key: String) {
        _state.update { it.copy(sessionId = id, encryptionKey = key) }
    }

    fun clearSession() {
        _state.update { it.copy(sessionId = null, encryptionKey = null) }
    }

    fun resetTestResult() {
        _state.update {
            it.copy(
                    phase = RunPhase.IDLE,
                    elapsedSeconds = 0,
                    wasCompleted = false,
                    wasCancelledByBackground = false,
                    overallStability = 100f,
                    chartPoints = emptyList(),
                    recordedStamps = emptyList(),
                    scorecard = null,
                    validityFlags = 0,
                    currentAttribution = Attribution.NONE,
                    coreImpacts = emptyList(),
                    thermalEvents = emptyList(),
                    initialBatteryLevel = 0,
                    initialBatteryTemp = 0f,
                    finalBatteryLevel = 0,
                    finalBatteryTemp = 0f,
                    cooldownElapsedSec = 0,
                    preRunIps = emptyList(),
                    calibrationMarkIdx = 0,
                    benchTest = null,
                    benchPlan = emptyList(),
                    benchProgress = 0f,
                    benchResult = null,
                    benchError = null,
                    uxTest = null,
                    aiTest = null,
                    aiVisual = null,
                    llmAccel = null,
                    llmReply = emptyList(),
                    uxProgress = 0f,
                    qrFrames = null,
                    qrFound = null
            )
        }
    }

    fun setThermalState(ts: ThermalState) {
        val prev = _state.value.currentThermalState
        _state.update { it.copy(currentThermalState = ts) }
        // Event log = transitions only, timestamped at occurrence.
        val s = _state.value
        if (ts != prev && s.isRunning && s.phase == RunPhase.MEASURED) {
            addThermalEvent(ts, s.elapsedSeconds.toFloat())
        }
    }

    // ── Pre-flight gates (blocking) ──────────────────────────────────────────

    suspend fun runPreflight(): PreflightReport = withContext(Dispatchers.IO) {
        val gates = mutableListOf<GateResult>()
        var hints = 0

        // Background CPU load — device must be idle enough to attribute slowdowns.
        val cpuA = StressProbes.readSystemCpu()
        delay(BG_SAMPLE_MS)
        val cpuB = StressProbes.readSystemCpu()
        val busy = StressProbes.busyFraction(cpuA, cpuB)
        gates += GateResult(
            "bgload", "BACKGROUND CPU LOAD", blocking = true,
            passed = busy == null || busy <= GATE_BG_BUSY,
            detail = if (busy == null) "System load unreadable — allowed with caution"
                     else "System busy %.0f%% (limit %d%%)".format(busy * 100, (GATE_BG_BUSY * 100).toInt())
        )
        if (busy != null && busy > GATE_BG_BUSY) hints = hints or ValidityFlag.INTERFERENCE

        // Starting temperature — informational only. The COOLDOWN phase handles
        // warm devices; the gate never blocks or fails, it just reports state.
        val zones = StressProbes.readThermalZones()
        val cpuTemp = StressProbes.cpuAdjacentTemp(zones)
        gates += GateResult(
            "starttemp", "STARTING TEMPERATURE", blocking = false, passed = true,
            detail = when {
                cpuTemp == null -> "Thermal sensors unreadable"
                cpuTemp <= GATE_TEMP_C -> "Warmest CPU zone %.1f°C".format(cpuTemp)
                else -> "Warmest CPU zone %.1f°C — cooldown will run first".format(cpuTemp)
            }
        )

        // Thermal headroom — the platform's own forecast: 0 = cool, 1.0 = severe throttling. Informational
        // only: a hot start never blocks, the run is flagged instead.
        val hr = StressProbes.headroom(powerManager)
        gates += GateResult(
            "headroom", "THERMAL HEADROOM", blocking = false,
            passed = hr == null || hr < GATE_HEADROOM,
            detail = if (hr == null) "Headroom API unavailable (API < 30)"
                     else if (hr < GATE_HEADROOM) "Thermal load %.2f — throttling starts at 1.00".format(hr)
                     else "Thermal load %.2f — close to throttling, the cooldown will run first".format(hr)
        )

        // Power source.
        val batt = StressProbes.battery(appContext)
        gates += GateResult(
            "charging", "CHARGER", blocking = true, passed = !batt.charging,
            detail = if (batt.charging) "Charger connected — unplug to run" else "On battery"
        )
        gates += GateResult(
            "level", "BATTERY LEVEL", blocking = true, passed = batt.level >= GATE_BATTERY_PCT,
            detail = "Battery %d%% (min %d%%)".format(batt.level, GATE_BATTERY_PCT)
        )

        // Frequency readability — without it slowdowns can't be verified as DVFS.
        val freqOk = StressProbes.anyFreqReadable(coreCount)
        gates += GateResult(
            "freq", "CLOCK TELEMETRY", blocking = false, passed = freqOk,
            detail = if (freqOk) "cpufreq sysfs readable" else "cpufreq unreadable — attribution degraded"
        )
        if (!freqOk) hints = hints or ValidityFlag.FREQ_MISSING

        // Advisories (never block).
        gates += GateResult(
            "saver", "BATTERY SAVER", blocking = false, passed = !isBatterySaverOn(),
            detail = if (isBatterySaverOn()) "Saver ON — clocks capped, run flagged"
                    else "Battery Saver off"
        )
        if (isBatterySaverOn()) hints = hints or ValidityFlag.ENV_CAPPED
        // Mobile data blocks the start: a busy modem sits next to the SoC and heats it. Airplane mode or
        // Wi-Fi keep it quiet.
        val mobileData = isCellularActive()
        gates += GateResult(
            "cellular", "MOBILE DATA", blocking = true, passed = !mobileData,
            detail = when {
                mobileData -> "On mobile data — switch to Wi-Fi or airplane mode"
                isAirplaneModeOn() -> "Airplane mode"
                else -> "Mobile data not in use"
            }
        )

        // GPU renderer probe — the 3D channel needs GLES 3.x + EGL.
        val glVer = try {
            (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .deviceConfigurationInfo.reqGlEsVersion
        } catch (_: Exception) { 0 }
        gates += GateResult(
            "gpu", "GPU RENDERER", blocking = false,
            passed = glVer >= 0x30000,
            detail = if (glVer >= 0x30000) "OpenGL ES ${glVer shr 16}.${glVer and 0xFFFF} — 3D benchmark available"
                     else "GLES 3.x unavailable — GPU 3D mode will not run"
        )

        val canRun = gates.filter { it.blocking }.all { it.passed }
        PreflightReport(gates, canRun, hints)
    }

    // ── Internal: snapshot & window machinery ─────────────────────────────────

    private data class Snap(
        val tNs: Long,
        val counters: LongArray,
        val taskStats: Array<StressProbes.TaskStat?>,
        val freqs: List<StressProbes.CoreFreq>,
        val zones: List<StressProbes.TempZone>,
        val batt: StressProbes.BattSnap,
        val headroom: Float?,
        val thermalStatus: ThermalState,
        val brightness: Int?,
        val batterySaver: Boolean
    )

    /** A window = delta between two snaps plus point-in-time values at its end. */
    private data class Win(
        val dtNs: Long,
        val itersPerWorker: LongArray,
        val ipsPerCluster: DoubleArray,
        val littleShare: Double,
        val schedFracAvg: Float,
        val capRatioAvg: Float,
        val minCapRatio: Float,
        val freqRatioAvg: Float,
        val freqRatioPerCore: IntArray,
        val offlineCores: Int,
        val cpuTempC: Float?,
        val skinTempC: Float?,
        val battTempC: Float,
        val battCurrentUa: Int?,
        val battVoltageMv: Int,
        val headroom: Float?,
        val thermalStatus: ThermalState,
        val envFlags: Int
    )

    private fun snapshot(): Snap {
        val ctr = LongArray(coreCount) { counters[it].get() }
        val stats = Array<StressProbes.TaskStat?>(coreCount) { i ->
            val tid = workerTids[i]
            if (tid > 0) StressProbes.readTaskStat(tid) else null
        }
        val freqs = StressProbes.readCpuFreqs(coreCount)
        val zones = StressProbes.readThermalZones()
        val batt = StressProbes.battery(appContext)
        return Snap(
            tNs = System.nanoTime(),
            counters = ctr,
            taskStats = stats,
            freqs = freqs,
            zones = zones,
            batt = batt,
            headroom = StressProbes.headroom(powerManager),
            thermalStatus = _state.value.currentThermalState,
            brightness = StressProbes.screenBrightness(appContext),
            batterySaver = isBatterySaverOn()
        )
    }

    private fun diffWindow(a: Snap, b: Snap): Win {
        val dt = max(1L, b.tNs - a.tNs)

        val iters = LongArray(coreCount) { i ->
            max(0L, b.counters[i] - a.counters[i])
        }

        // Cluster-attributed throughput via per-worker residency (task stat field 39).
        val ipsPerCluster = DoubleArray(topology.clusters.size.coerceAtLeast(1))
        var littleIps = 0.0
        var totalIps = 0.0
        for (i in 0 until coreCount) {
            val ips = iters[i] * 1e9 / dt
            totalIps += ips
            val cpu = b.taskStats[i]?.processor ?: -1
            val cl = if (cpu >= 0) topology.clusterOf(cpu) else -1
            if (cl >= 0 && cl < ipsPerCluster.size) ipsPerCluster[cl] += ips
            if (cl == topology.slowestClusterIdx) littleIps += ips
        }
        val littleShare = if (totalIps > 0) littleIps / totalIps else 0.0

        // Scheduled fraction over active workers.
        var schedSum = 0.0
        var schedN = 0
        for (i in 0 until activeThreadCount) {
            val sA = a.taskStats[i]; val sB = b.taskStats[i]
            if (sA != null && sB != null) {
                schedSum += StressProbes.scheduledFraction(
                    (sB.utime - sA.utime) + (sB.stime - sA.stime), dt
                )
                schedN++
            }
        }
        val schedFracAvg = if (schedN > 0) (schedSum / schedN).toFloat() else 1f

        // Frequency ratios at window end.
        var capSum = 0.0; var minCap = 1.0; var freqSum = 0.0; var freqN = 0
        var offline = 0
        val perCore = IntArray(coreCount)
        for (cf in b.freqs) {
            if (cf.hwMaxKHz <= 0) continue
            if (!cf.online) { offline++; continue }
            val capR = if (cf.capKHz > 0) cf.capKHz.toDouble() / cf.hwMaxKHz else 1.0
            val curR = cf.curKHz.toDouble() / cf.hwMaxKHz
            capSum += capR; freqSum += curR; freqN++
            if (capR < minCap) minCap = capR
            perCore[cf.core] = (curR * 100).toInt().coerceIn(0, 100)
        }
        val capRatioAvg = if (freqN > 0) (capSum / freqN).toFloat() else 1f
        val freqRatioAvg = if (freqN > 0) (freqSum / freqN).toFloat() else 1f
        if (freqN == 0) minCap = 1.0

        var env = 0
        if (b.batt.charging) env = env or EnvFlag.CHARGING
        val sb = startBrightness
        if (sb != null && b.brightness != null && b.brightness != sb) env = env or EnvFlag.BRIGHTNESS_CHANGED
        if (b.batterySaver) env = env or EnvFlag.BATTERY_SAVER

        return Win(
            dtNs = dt,
            itersPerWorker = iters,
            ipsPerCluster = ipsPerCluster,
            littleShare = littleShare,
            schedFracAvg = schedFracAvg,
            capRatioAvg = capRatioAvg,
            minCapRatio = minCap.toFloat(),
            freqRatioAvg = freqRatioAvg,
            freqRatioPerCore = perCore,
            offlineCores = offline,
            cpuTempC = StressProbes.cpuAdjacentTemp(b.zones),
            skinTempC = StressProbes.skinTemp(b.zones),
            battTempC = b.batt.tempC,
            battCurrentUa = b.batt.currentUa,
            battVoltageMv = b.batt.voltageMv,
            headroom = b.headroom,
            thermalStatus = b.thermalStatus,
            envFlags = env
        )
    }

    /** GPU frames rendered within dtNs (fractional — see frameProgress) — −1 when the channel is off. */
    private fun gpuFpsDelta(dtNs: Long, runner: GpuRunner = gpuBench): Double {
        if (!runner.running || dtNs <= 0) return -1.0
        val f = runner.frameProgress()
        val df = (f - gpuFramesPrev).coerceAtLeast(0.0)
        gpuFramesPrev = f
        return df * 1e9 / dtNs
    }

    private fun p95(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[min(s.size - 1, ceil(0.95 * s.size).toInt() - 1).coerceAtLeast(0)]
    }

    private fun classify(
        aggMinCap: Float,
        aggSchedFrac: Float,
        aggLittleShare: Double,
        aggFreqRatio: Float,
        aggOffline: Int,
        littleShareCal: Double,
        freqMissing: Boolean
    ): Attribution = when {
        freqMissing -> Attribution.UNKNOWN
        aggOffline > 0 -> Attribution.HOTPLUG
        aggMinCap < CAP_THROTTLE -> Attribution.DVFS_CAP
        aggSchedFrac < SCHED_STARVED -> Attribution.CONTENTION
        aggLittleShare > littleShareCal + MIGRATION_DELTA -> Attribution.MIGRATION
        aggFreqRatio < FREQ_LOW -> Attribution.GOVERNOR
        else -> Attribution.NONE
    }

    /** GPU-channel attribution — devfreq clock ratio + thermal-status evidence. */
    private fun classifyGpu(gpuFreqRatio: Float, thermalOrdinal: Int, belowBaseline: Boolean): Attribution = when {
        gpuFreqRatio in 0f..CAP_THROTTLE -> Attribution.DVFS_CAP
        gpuFreqRatio < 0f -> if (belowBaseline && thermalOrdinal >= 2) Attribution.DVFS_CAP else if (belowBaseline) Attribution.UNKNOWN else Attribution.NONE
        belowBaseline && thermalOrdinal >= 2 -> Attribution.DVFS_CAP
        belowBaseline -> Attribution.UNKNOWN
        else -> Attribution.NONE
    }

    // ── Phased session ─────────────────────────────────────────────────────────

    /**
     * COOLDOWN — zero load; wait for the SoC to shed heat to a fair start. Exits when the
     * target is reached, sensors are unreadable, the temperature plateaus, or at the hard cap.
     * Returns false when it gave up warm (the run is then flagged COOLDOWN_TIMEOUT).
     */
    private suspend fun coolDown(): Boolean {
        val t0 = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones()) ?: return true
        if (t0 <= COOLDOWN_TARGET_C) return true
        var prevT = t0
        var waitedMs = 0L
        val shownSec = _state.value.cooldownElapsedSec      // one timer across the whole wait
        var stallMs = 0L
        while (_state.value.isRunning && waitedMs < COOLDOWN_MAX_MS) {
            delay(1_000L); waitedMs += 1_000L
            val t = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones()) ?: return true  // can't measure — don't gate
            if (t <= COOLDOWN_TARGET_C) return true
            stallMs = if (t < prevT - COOLDOWN_STALL_DELTA) 0L else stallMs + 1_000L
            if (stallMs >= COOLDOWN_STALL_MS) return false   // plateaued at warm floor
            prevT = t
            _state.update { it.copy(cooldownElapsedSec = shownSec + (waitedMs / 1_000L).toInt()) }
        }
        return false
    }

    /**
     * Holds the benchmark while something else is eating the CPU — Android compiling the app
     * just after it was installed, an app update installing — so nobody has to know to wait.
     * Part of the cooldown on screen (one "getting ready" timer). A share that holds steady below the
     * bar is the phone's normal, not work that will pass (a real phone never gives an app every core),
     * so the run starts. Not quiet if it never settled within a minute (the run is flagged).
     */
    private suspend fun waitUntilQuiet(): QuietCheck {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val shownSec = _state.value.cooldownElapsedSec
        var share = 1f
        val recent = ArrayDeque<Float>()
        fun check(quiet: Boolean) = QuietCheck(quiet, share, ((android.os.SystemClock.elapsedRealtime() - t0) / 1_000L).toInt())
        try {
            while (_state.value.isRunning) {
                share = withContext(Dispatchers.Default) { StressProbes.cpuShare() }
                if (share >= QUIET_SHARE) return check(true)
                recent.addLast(share)
                if (recent.size > 3) recent.removeFirst()
                if (recent.size == 3 && recent.max() - recent.min() <= QUIET_STEADY) return check(true)
                val waitedMs = android.os.SystemClock.elapsedRealtime() - t0
                if (waitedMs >= QUIET_MAX_MS) return check(false)
                _state.update { it.copy(busyWait = true, cooldownElapsedSec = shownSec + (waitedMs / 1_000L).toInt()) }
                delay(QUIET_POLL_MS)
            }
            return check(true)
        } finally {
            _state.update { it.copy(busyWait = false) }
        }
    }

    private class QuietCheck(val quiet: Boolean, val share: Float, val waitedSec: Int)

    private suspend fun runSession() {
        val reachedTarget = coolDown()
        if (!_state.value.isRunning) return
        val cooldownTimedOut = !reachedTarget

        val mode = _state.value.stressMode
        if (mode.usesCpu) startWorkers()
        if (mode.usesGpu) {
            gpuBench.start()
            gpuFramesPrev = 0.0
            _state.update { it.copy(gpuName = gpuBench.gpuName, gpuActive = true) }
        }

        // ── WARM-UP — settles ART JIT + governor; unrecorded as stamps but the
        // throughput trace IS surfaced as the chart's ghost prefix so users see
        // the ramp → peak → collapse that precedes measurement.
        _state.update { it.copy(phase = RunPhase.WARMUP) }
        var prev = snapshot()
        var remaining = WARMUP_MS
        while (remaining > 0 && _state.value.isRunning && threadAlive.get()) {
            delay(min(SAMPLE_MS, remaining)); remaining -= SAMPLE_MS
            val cur = snapshot()
            val dt = cur.tNs - prev.tNs
            if (dt > 0) {
                var tot = 0.0
                for (i in 0 until coreCount) tot += (cur.counters[i] - prev.counters[i]) * 1e9 / dt
                val gfps = gpuFpsDelta(dt)
                _state.update {
                    it.copy(
                        preRunIps = it.preRunIps + (if (mode.usesCpu) tot else gfps * 100),
                        preRunGpuFps = if (gfps >= 0) it.preRunGpuFps + gfps else it.preRunGpuFps,
                        gpuFps = gfps
                    )
                }
            }
            prev = cur
        }
        if (!_state.value.isRunning) return

        // ── CALIBRATION — capture the boost window; p95 becomes the baseline.
        _state.update {
            it.copy(phase = RunPhase.CALIBRATION, calibrationMarkIdx = it.preRunIps.size)
        }
        prev = snapshot()
        val calIpsTotal = mutableListOf<Double>()
        val calGpuFps = mutableListOf<Double>()
        val calIpsWorker = List(coreCount) { mutableListOf<Double>() }
        val calIpsCluster = List(topology.clusters.size.coerceAtLeast(1)) { mutableListOf<Double>() }
        val calCaps = mutableListOf<Float>()
        val calLittleShares = mutableListOf<Double>()
        var calElapsed = 0L
        while (calElapsed < CALIBRATION_MS * 1_000_000L && _state.value.isRunning) {
            delay(SAMPLE_MS)
            val cur = snapshot()
            val win = diffWindow(prev, cur)
            var tot = 0.0
            for (i in 0 until coreCount) {
                val ips = win.itersPerWorker[i] * 1e9 / win.dtNs
                calIpsWorker[i] += ips; tot += ips
            }
            val gfps = gpuFpsDelta(win.dtNs)
            if (gfps >= 0) calGpuFps += gfps
            // GPU-mode primary channel = fps × 100 (keeps integer stamps resolvable to 0.01 fps)
            calIpsTotal += if (mode.usesCpu) tot else gfps * 100
            for (k in calIpsCluster.indices) calIpsCluster[k] += win.ipsPerCluster[k]
            calCaps += win.minCapRatio
            calLittleShares += win.littleShare
            calElapsed += win.dtNs
            prev = cur
            _state.update {
                it.copy(
                    preRunIps = it.preRunIps + (if (mode.usesCpu) tot else gfps * 100),
                    preRunGpuFps = if (gfps >= 0) it.preRunGpuFps + gfps else it.preRunGpuFps,
                    gpuFps = gfps
                )
            }
        }
        if (!_state.value.isRunning) return

        val baseline = p95(calIpsTotal)
        val gpuBaseline = p95(calGpuFps)
        val workerBaseline = calIpsWorker.map { p95(it).coerceAtLeast(1.0) }
        val littleShareCal = calLittleShares.sorted().getOrElse(calLittleShares.size / 2) { 0.0 }
        val warmStarted = calCaps.isNotEmpty() && calCaps.count { it < 0.99f } > calCaps.size / 2

        // ── MEASURED RUN ────────────────────────────────────────────────────────
        _state.update {
            it.copy(phase = RunPhase.MEASURED, baselineIps = baseline,
                    gpuBaselineFps = gpuBaseline, gpuName = gpuBench.gpuName)
        }
        val measuredStartNs = System.nanoTime()
        val durationNs = (state.value.testDuration.seconds ?: 300) * 1_000_000_000L
        var freqEverReadable = false
        var contentionStamps = 0
        var powerEvent = false

        while (System.nanoTime() - measuredStartNs < durationNs && _state.value.isRunning) {
            // Aggregate ~1 s of sub-windows into one stamp.
            val stampStart = System.nanoTime()
            val accIters = LongArray(coreCount)
            val accClusterIps = DoubleArray(topology.clusters.size.coerceAtLeast(1))
            var accDt = 0L
            var capSum = 0.0; var minCap = 1f; var freqSum = 0.0
            var schedSum = 0.0; var littleSum = 0.0; var nWins = 0
            var maxCpuTemp = Float.MIN_VALUE; var skinTemp = -1f
            var battTempSum = 0.0; var lastBattMv = 0; var lastBattUa: Int? = null
            var minHeadroom: Float? = null
            var lastStatus = ThermalState.NOMINAL
            var lastPerCore = IntArray(0); var lastOffline = 0; var env = 0
            var lastWinDt = 0L

            val gpuFramesAtStart = gpuBench.frameProgress()
            var gpuFreqAcc = 0f; var gpuFreqN = 0

            while (System.nanoTime() - stampStart < STAMP_MS * 1_000_000L && _state.value.isRunning) {
                delay(SAMPLE_MS)
                val cur = snapshot()
                val win = diffWindow(prev, cur)
                prev = cur
                if (win.dtNs <= 0) continue
                gpuFpsDelta(win.dtNs) // keeps live-FPS state fresh for the reactor
                val gfr = gpuBench.gpuFreqRatio()
                if (gfr >= 0) { gpuFreqAcc += gfr; gpuFreqN++ }
                accDt += win.dtNs; lastWinDt = win.dtNs; nWins++
                for (i in 0 until coreCount) accIters[i] += win.itersPerWorker[i]
                for (k in accClusterIps.indices) accClusterIps[k] += win.ipsPerCluster[k] * win.dtNs / 1e9
                capSum += win.capRatioAvg; freqSum += win.freqRatioAvg
                if (win.minCapRatio < minCap) minCap = win.minCapRatio
                schedSum += win.schedFracAvg; littleSum += win.littleShare
                win.cpuTempC?.let { if (it > maxCpuTemp) maxCpuTemp = it }
                win.skinTempC?.let { skinTemp = it }
                battTempSum += win.battTempC
                lastBattMv = win.battVoltageMv; lastBattUa = win.battCurrentUa ?: lastBattUa
                win.headroom?.let { h -> minHeadroom = minHeadroom?.let { min(it, h) } ?: h }
                lastStatus = win.thermalStatus
                lastPerCore = win.freqRatioPerCore; lastOffline = win.offlineCores
                env = env or win.envFlags
                if (win.freqRatioPerCore.any { it > 0 }) freqEverReadable = true
            }
            if (nWins == 0) break

            // ── GPU channel this stamp ──
            val gpuFramesNow = gpuBench.frameProgress()
            val stampDurNs = System.nanoTime() - stampStart
            val gpuFpsStamp = if (mode.usesGpu)
                (gpuFramesNow - gpuFramesAtStart).coerceAtLeast(0.0) * 1e9 / stampDurNs else -1.0
            val gpuP95 = if (mode.usesGpu)
                gpuBench.frameP95Ms((gpuFramesNow - gpuFramesAtStart).toInt().coerceAtLeast(1)) else -1f
            val gpuFreqR = if (gpuFreqN > 0) gpuFreqAcc / gpuFreqN else -1f

            val cpuIps = (accIters.sum() * 1e9 / accDt).toLong()
            // Primary channel: CPU iterations when workers run, GPU fps×100 otherwise —
            // so the whole scorecard/baseline/onset pipeline applies unchanged.
            val ipsTotal = if (mode.usesCpu) cpuIps else (gpuFpsStamp * 100).toLong()
            val ipsPerCluster = accClusterIps.map { (it * 1e9 / accDt).toLong() }
            val nowNs = System.nanoTime() - measuredStartNs
            val aggMinCap = minCap
            val aggSched = (schedSum / nWins).toFloat()
            val aggLittle = littleSum / nWins
            val aggFreq = (freqSum / nWins).toFloat()
            val freqMissing = if (mode.usesCpu) !freqEverReadable else gpuFreqR < 0
            val attribution = if (mode.usesCpu) classify(
                aggMinCap, aggSched, aggLittle, aggFreq, lastOffline,
                littleShareCal, freqMissing
            ) else classifyGpu(gpuFreqR, lastStatus.ordinal, gpuFpsStamp < gpuBaseline * 0.9)
            if (attribution == Attribution.CONTENTION) contentionStamps++
            if (env and EnvFlag.CHARGING != 0) powerEvent = true

            val stamp = StampV2(
                tNs = nowNs,
                windowNs = accDt,
                ipsTotal = ipsTotal,
                gpuFps = gpuFpsStamp,
                gpuFrameP95Ms = gpuP95,
                gpuFreqRatio = gpuFreqR,
                ipsPerCluster = ipsPerCluster,
                capRatioAvg = (capSum / nWins).toFloat(),
                minCapRatio = aggMinCap,
                freqRatioAvg = aggFreq,
                freqRatioPerCore = lastPerCore.toList(),
                offlineCores = lastOffline,
                schedFracAvg = aggSched,
                cpuTempMilliC = if (maxCpuTemp > Float.MIN_VALUE) (maxCpuTemp * 1000).toInt() else 0,
                skinTempMilliC = if (skinTemp >= 0) (skinTemp * 1000).toInt() else -1,
                battTempMilliC = (battTempSum / nWins * 1000).toInt(),
                battCurrentUa = lastBattUa,
                battVoltageMv = lastBattMv,
                thermalHeadroom = minHeadroom,
                thermalStatus = lastStatus.ordinal,
                attribution = attribution.code,
                envFlags = env
            )

            _state.update { st ->
                val ratio = if (baseline > 0) (ipsTotal / baseline * 100).toFloat().coerceIn(0f, 110f) else 100f
                val impacts = List(coreCount) { i ->
                    if (i < activeThreadCount) {
                        val wips = accIters[i] * 1e9 / accDt
                        (wips / workerBaseline[i] * 100).toFloat().coerceIn(0f, 110f)
                    } else 0f
                }
                val elapsed = (nowNs / 1_000_000_000L).toInt()
                st.copy(
                    elapsedSeconds = elapsed,
                    overallStability = ratio,
                    gpuFps = gpuFpsStamp,
                    gpuName = st.gpuName.ifEmpty { gpuBench.gpuName },
                    coreImpacts = impacts,
                    currentAttribution = attribution,
                    capRatioAvg = stamp.capRatioAvg,
                    freqRatioAvg = stamp.freqRatioAvg,
                    thermalHeadroom = stamp.thermalHeadroom ?: st.thermalHeadroom,
                    chartPoints = st.chartPoints + StabilityPoint(elapsed.toFloat(), ratio),
                    recordedStamps = st.recordedStamps + stamp
                )
            }
        }

        // ── REPORT ──────────────────────────────────────────────────────────────
        val s = _state.value
        val completed = s.isRunning &&
            System.nanoTime() - measuredStartNs >= durationNs
        val targetNs = durationNs
        val actualNs = System.nanoTime() - measuredStartNs

        gpuBench.stop()

        val card = buildScorecard(
            baseline = baseline,
            littleShareCal = littleShareCal,
            warmStarted = warmStarted,
            cooldownTimedOut = cooldownTimedOut,
            freqMissing = if (mode.usesCpu) !freqEverReadable else gpuBaseline <= 0,
            contentionStamps = contentionStamps,
            powerEvent = powerEvent,
            shortRun = actualNs < targetNs * 9 / 10,
            saverDuringRun = batterySaverAtStart,
            gpuBaselineFps = gpuBaseline,
            gpuName = gpuBench.gpuName
        )

        _state.update {
            it.copy(
                scorecard = card,
                validityFlags = card.validityFlags,
                wasCompleted = completed && s.isRunning
            )
        }
        stopTest()
    }

    // ── Scoring ──────────────────────────────────────────────────────────────────

    private fun buildScorecard(
        baseline: Double,
        littleShareCal: Double,
        warmStarted: Boolean,
        cooldownTimedOut: Boolean,
        freqMissing: Boolean,
        contentionStamps: Int,
        powerEvent: Boolean,
        shortRun: Boolean,
        saverDuringRun: Boolean,
        gpuBaselineFps: Double = 0.0,
        gpuName: String = ""
    ): Scorecard {
        val stamps = _state.value.recordedStamps
        if (stamps.isEmpty() || baseline <= 0) {
            var flags = ValidityFlag.SHORT_RUN
            if (freqMissing) flags = flags or ValidityFlag.FREQ_MISSING
            return Scorecard(baselineIps = baseline, confidence = 0, validityFlags = flags)
        }

        // Delivered capacity — area under the throughput curve vs baseline potential.
        var workDone = 0.0; var windowSum = 0.0
        var contentionN = 0; var thermalN = 0
        var capNeverDropped = true
        var minHeadroomSeen: Float? = null
        var worstStatusOrdinal = 0
        var mwSum = 0.0; var mwN = 0; var ipsSum = 0.0
        var firstCpuT: Float? = null; var maxCpuT = 0f
        for (s in stamps) {
            workDone += s.ipsTotal * s.windowNs
            windowSum += s.windowNs
            ipsSum += s.ipsTotal
            if (Attribution.of(s.attribution) == Attribution.CONTENTION) contentionN++
            if (Attribution.of(s.attribution).isThermal) thermalN++
            if (s.minCapRatio < CAP_THROTTLE) capNeverDropped = false
            s.thermalHeadroom?.let { h -> minHeadroomSeen = minHeadroomSeen?.let { min(it, h) } ?: h }
            if (s.thermalStatus > worstStatusOrdinal) worstStatusOrdinal = s.thermalStatus
            if (s.battCurrentUa != null && s.battVoltageMv > 0) {
                mwSum += s.battCurrentUa.toDouble() * s.battVoltageMv / 1000.0
                mwN++
            }
            val t = s.cpuTempMilliC / 1000f
            if (t > 0) { if (firstCpuT == null) firstCpuT = t; if (t > maxCpuT) maxCpuT = t }
        }
        val delivered = ((workDone / windowSum) / baseline * 100).toFloat().coerceIn(0f, 150f)

        // Sustained ratio — median of the last quartile vs baseline.
        val q = stamps.takeLast(max(1, stamps.size / 4))
        val sustained = (q.map { it.ipsTotal / baseline }.sorted()
            .let { it[it.size / 2] } * 100).toFloat().coerceIn(0f, 150f)

        // Debounced throttle onset: first <90% held for 3 consecutive stamps.
        var onset: Int? = null
        for (i in 0 until stamps.size - ONSET_DEBOUNCE + 1) {
            if ((0 until ONSET_DEBOUNCE).all { j ->
                    stamps[i + j].ipsTotal < baseline * ONSET_RATIO
                }) { onset = stamps[i].elapsedMs / 1000; break }
            }

        // Min sustained — 5 s sliding mean over thermal-attributed samples only.
        val thermalRatios = stamps.filter { Attribution.of(it.attribution).isThermal }
            .map { (it.ipsTotal / baseline * 100).toFloat() }
        val minSustained = if (thermalRatios.size >= 5) {
            thermalRatios.windowed(5).minOf { it.average() }.toFloat()
        } else if (thermalRatios.isNotEmpty()) thermalRatios.min() else null

        val timeInThrottle = thermalN * 100f / stamps.size

        // Perf per watt from instantaneous current × voltage.
        val perfPerWatt = if (mwN > 0 && mwSum > 0)
            ((ipsSum / stamps.size) / (mwSum / mwN)).toFloat() else null

        val deltaT = maxCpuT - (firstCpuT ?: maxCpuT)
        val thermalEff = if (maxCpuT > 0) delivered / max(1f, deltaT) else null

        // Validity bitfield. A warm start isn't one of them: phones idle at different temperatures (45–50 °C is
        // normal for some), so it lowers the confidence instead of flagging the run.
        var flags = 0
        if (contentionN > stamps.size / 10) flags = flags or ValidityFlag.INTERFERENCE
        if (powerEvent) flags = flags or ValidityFlag.POWER_EVENT
        if (freqMissing) flags = flags or ValidityFlag.FREQ_MISSING
        if (shortRun) flags = flags or ValidityFlag.SHORT_RUN
        if (saverDuringRun) flags = flags or ValidityFlag.ENV_CAPPED
        // Suspicious boost: huge unrepeatable peak with no visible cap mitigation.
        if (sustained < 60f && capNeverDropped &&
            ((minHeadroomSeen != null && minHeadroomSeen!! <= 0.05f) || worstStatusOrdinal >= 2))
            flags = flags or ValidityFlag.SUSPICIOUS_BOOST
        // Baseline exceeded: measured throughput sustained above the calibration
        // peak means the baseline was captured while capped/starved/misparked.
        if (stamps.count { it.ipsTotal > baseline * 1.05 } >= 3)
            flags = flags or ValidityFlag.BASELINE_EXCEEDED

        // Confidence score per spec §11.
        var conf = 100
        if (freqMissing) conf -= 30
        if (flags and ValidityFlag.INTERFERENCE != 0) conf -= 25
        if (warmStarted) conf -= 25
        if (cooldownTimedOut) conf -= 15
        if (flags and ValidityFlag.BASELINE_EXCEEDED != 0) conf -= 10
        if (powerEvent) conf -= 15
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) conf -= 10
        if (stamps.all { it.cpuTempMilliC == 0 }) conf -= 10
        if (perfPerWatt == null) conf -= 5
        conf = conf.coerceIn(0, 100)

        // ── GPU channel scorecard (stamps carry fps/ips of the primary channel) ──
        val gpuSustained: Float
        val gpuDelivered: Float
        if (gpuBaselineFps > 0 && stamps.any { it.gpuFps >= 0 }) {
            val gq = stamps.takeLast(max(1, stamps.size / 4))
                .map { it.gpuFps / gpuBaselineFps }.sorted()
            gpuSustained = (gq[gq.size / 2] * 100).toFloat().coerceIn(0f, 150f)
            var gw = 0.0; var gws = 0.0
            for (s in stamps) if (s.gpuFps >= 0) { gw += s.gpuFps * s.windowNs; gws += s.windowNs }
            gpuDelivered = ((gw / gws) / gpuBaselineFps * 100).toFloat().coerceIn(0f, 150f)
        } else { gpuSustained = 0f; gpuDelivered = 0f }

        return Scorecard(
            baselineIps = baseline,
            sustainedRatio = sustained,
            deliveredCapacity = delivered,
            minSustained = minSustained,
            throttleOnsetSec = onset,
            timeInThrottlePct = timeInThrottle,
            perfPerWatt = perfPerWatt,
            thermalEfficiency = thermalEff,
            confidence = conf,
            validityFlags = flags,
            warmStarted = warmStarted,
            gpuBaselineFps = gpuBaselineFps,
            gpuDeliveredCapacity = gpuDelivered,
            gpuSustainedRatio = gpuSustained,
            gpuName = gpuName
        )
    }

    private fun addThermalEvent(state: ThermalState, time: Float) {
        val event = ThermalEvent(time, state)
        _state.update { it.copy(thermalEvents = it.thermalEvents + event) }
    }

    // ── Device / system checks ────────────────────────────────────────────────

    fun isCharging(): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = appContext.registerReceiver(null, filter) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isBatterySaverOn(): Boolean = powerManager.isPowerSaveMode

    /**
     * The phone's connection runs over mobile data. Asks ConnectivityManager, which needs no extra
     * permission — TelephonyManager.networkType needs READ_PHONE_STATE on Android 11+ and threw.
     */
    fun isCellularActive(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
    } catch (_: Exception) {
        false
    }

    fun isAirplaneModeOn(): Boolean =
        android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

    /** Exact build id (e.g. "BP4A.251205.006") — tells monthly updates apart; RELEASE alone cannot. */
    fun getOsBuild(): String = Build.ID

    /** Android security patch level (e.g. "2026-10-05"); may be blank on some builds. */
    fun getSecurityPatch(): String = Build.VERSION.SECURITY_PATCH

    fun getSocModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }

    fun getThermalStateFromSystem(): ThermalState = StressProbes.thermalStatus(powerManager)
}
