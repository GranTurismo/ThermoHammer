package com.example.thermohammer.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val preRunIps: List<Double> = emptyList(),      // raw IPS per 250ms window — warmup+calibration
        val calibrationMarkIdx: Int = 0                  // index in preRunIps where calibration began
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
        const val GATE_HEADROOM = 0.5f         // min getThermalHeadroom()
        const val GATE_BATTERY_PCT = 15        // min battery level
        const val GATE_BG_BUSY = 0.15f         // max background CPU busy fraction
        const val BG_SAMPLE_MS = 3_000L        // pre-flight background-load window

        const val COOLDOWN_TARGET_C = 40f      // wait until CPU-adjacent zones reach this
        const val COOLDOWN_MAX_MS = 120_000L   // hard cap — flag & proceed beyond this
        const val COOLDOWN_STALL_MS = 30_000L  // plateau detection window
        const val COOLDOWN_STALL_DELTA = 0.5f  // <0.5°C improvement in window = plateaued
        const val GATE_TEMP_HARD_C = 55f       // pre-flight still blocks above this
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
            threadingType: StressThreadingType = StressThreadingType.MULTI
    ) {
        if (_state.value.isRunning) return

        activeThreadCount =
            if (threadingType == StressThreadingType.SINGLE) 1 else coreCount
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

    fun stopTest() {
        if (!_state.value.isRunning) return
        threadAlive.set(false)
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
                    calibrationMarkIdx = 0
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

        // Starting temperature — the COOLDOWN phase handles warm devices, so the
        // gate is advisory unless the SoC is dangerously hot already.
        val zones = StressProbes.readThermalZones()
        val cpuTemp = StressProbes.cpuAdjacentTemp(zones)
        gates += GateResult(
            "starttemp", "STARTING TEMPERATURE",
            blocking = cpuTemp != null && cpuTemp >= GATE_TEMP_HARD_C,
            passed = cpuTemp == null || cpuTemp <= GATE_TEMP_C,
            detail = when {
                cpuTemp == null -> "Thermal sensors unreadable — run flagged unverified"
                cpuTemp <= GATE_TEMP_C -> "Warmest CPU zone %.1f°C".format(cpuTemp)
                cpuTemp < GATE_TEMP_HARD_C ->
                    "Warmest CPU zone %.1f°C — cooldown will run first".format(cpuTemp)
                else -> "Warmest CPU zone %.1f°C — too hot to run".format(cpuTemp)
            }
        )
        if (cpuTemp != null && cpuTemp > GATE_TEMP_C) hints = hints or ValidityFlag.WARM_STARTED

        // Thermal headroom — kernel's own forecast.
        val hr = StressProbes.headroom(powerManager)
        gates += GateResult(
            "headroom", "THERMAL HEADROOM", blocking = hr != null,
            passed = hr == null || hr >= GATE_HEADROOM,
            detail = if (hr == null) "Headroom API unavailable (API < 29)"
                     else "Headroom %.2f (min %.2f)".format(hr, GATE_HEADROOM)
        )
        if (hr != null && hr < GATE_HEADROOM) hints = hints or ValidityFlag.WARM_STARTED

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
        gates += GateResult(
            "cellular", "CELLULAR RADIO", blocking = false, passed = !isCellularActive(),
            detail = if (isCellularActive()) "Cellular active — airplane mode recommended"
                    else "Radio offline"
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

    // ── Phased session ─────────────────────────────────────────────────────────

    private suspend fun runSession() {
        // ── COOLDOWN — zero load; wait for the SoC to shed heat to a fair start.
        // Exits when: target reached | sensors unreadable | plateau detected | hard cap.
        var reachedTarget = false
        var prevT = Float.MAX_VALUE
        run {
            val t0 = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones())
            if (t0 == null) { reachedTarget = true; return@run }
            if (t0 <= COOLDOWN_TARGET_C) { reachedTarget = true; return@run }
            prevT = t0
            var waitedMs = 0L
            var stallMs = 0L
            while (_state.value.isRunning && waitedMs < COOLDOWN_MAX_MS) {
                delay(1_000L); waitedMs += 1_000L
                val t = StressProbes.cpuAdjacentTemp(StressProbes.readThermalZones())
                if (t == null) { reachedTarget = true; break }   // can't measure — don't gate
                if (t <= COOLDOWN_TARGET_C) { reachedTarget = true; break }
                stallMs = if (t < prevT - COOLDOWN_STALL_DELTA) 0L else stallMs + 1_000L
                if (stallMs >= COOLDOWN_STALL_MS) break          // plateaued at warm floor
                prevT = t
                _state.update { it.copy(cooldownElapsedSec = (waitedMs / 1_000L).toInt()) }
            }
        }
        if (!_state.value.isRunning) return
        val cooldownTimedOut = !reachedTarget

        startWorkers()

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
                _state.update { it.copy(preRunIps = it.preRunIps + tot) }
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
            calIpsTotal += tot
            for (k in calIpsCluster.indices) calIpsCluster[k] += win.ipsPerCluster[k]
            calCaps += win.minCapRatio
            calLittleShares += win.littleShare
            calElapsed += win.dtNs
            prev = cur
            _state.update { it.copy(preRunIps = it.preRunIps + tot) }
        }
        if (!_state.value.isRunning) return

        val baseline = p95(calIpsTotal)
        val workerBaseline = calIpsWorker.map { p95(it).coerceAtLeast(1.0) }
        val littleShareCal = calLittleShares.sorted().getOrElse(calLittleShares.size / 2) { 0.0 }
        val warmStarted = calCaps.isNotEmpty() && calCaps.count { it < 0.99f } > calCaps.size / 2

        // ── MEASURED RUN ────────────────────────────────────────────────────────
        _state.update { it.copy(phase = RunPhase.MEASURED, baselineIps = baseline) }
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

            while (System.nanoTime() - stampStart < STAMP_MS * 1_000_000L && _state.value.isRunning) {
                delay(SAMPLE_MS)
                val cur = snapshot()
                val win = diffWindow(prev, cur)
                prev = cur
                if (win.dtNs <= 0) continue
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

            val ipsTotal = (accIters.sum() * 1e9 / accDt).toLong()
            val ipsPerCluster = accClusterIps.map { (it * 1e9 / accDt).toLong() }
            val nowNs = System.nanoTime() - measuredStartNs
            val aggMinCap = minCap
            val aggSched = (schedSum / nWins).toFloat()
            val aggLittle = littleSum / nWins
            val aggFreq = (freqSum / nWins).toFloat()
            val freqMissing = !freqEverReadable
            val attribution = classify(
                aggMinCap, aggSched, aggLittle, aggFreq, lastOffline,
                littleShareCal, freqMissing
            )
            if (attribution == Attribution.CONTENTION) contentionStamps++
            if (env and EnvFlag.CHARGING != 0) powerEvent = true

            val stamp = StampV2(
                tNs = nowNs,
                windowNs = accDt,
                ipsTotal = ipsTotal,
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

        val card = buildScorecard(
            baseline = baseline,
            littleShareCal = littleShareCal,
            warmStarted = warmStarted,
            cooldownTimedOut = cooldownTimedOut,
            freqMissing = !freqEverReadable,
            contentionStamps = contentionStamps,
            powerEvent = powerEvent,
            shortRun = actualNs < targetNs * 9 / 10,
            saverDuringRun = batterySaverAtStart
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
        saverDuringRun: Boolean
    ): Scorecard {
        val stamps = _state.value.recordedStamps
        if (stamps.isEmpty() || baseline <= 0) {
            var flags = ValidityFlag.SHORT_RUN
            if (warmStarted) flags = flags or ValidityFlag.WARM_STARTED
            if (cooldownTimedOut) flags = flags or ValidityFlag.COOLDOWN_TIMEOUT
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

        // Validity bitfield.
        var flags = 0
        if (warmStarted) flags = flags or ValidityFlag.WARM_STARTED
        if (cooldownTimedOut) flags = flags or ValidityFlag.COOLDOWN_TIMEOUT
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
            warmStarted = warmStarted
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

    fun isCellularActive(): Boolean {
        return try {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN &&
                    tm.simState == TelephonyManager.SIM_STATE_READY
        } catch (e: Exception) {
            false
        }
    }

    fun getAndroidVersion(): String = Build.VERSION.RELEASE

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
