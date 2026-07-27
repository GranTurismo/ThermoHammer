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
import com.example.thermohammer.network.DeviceHammerStamp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        val maxKHz: Long        // 0 if unreadable
) {
    val currentGHz: Double get() = currentKHz / 1_000_000.0
    val maxGHz: Double     get() = maxKHz     / 1_000_000.0
    /** 0‒100 % of max; returns null if max is unknown */
    val percentOfMax: Int? get() = if (maxKHz > 0) ((currentKHz.toDouble() / maxKHz) * 100).toInt().coerceIn(0, 100) else null
    val isOnline: Boolean  get() = currentKHz > 0
}

data class StressState(
        val isRunning: Boolean = false,
        val elapsedSeconds: Int = 0,
        val overallStability: Float = 100f,
        val coreImpacts: List<Float> = emptyList(),
        val chartPoints: List<StabilityPoint> = emptyList(),
        val thermalEvents: List<ThermalEvent> = emptyList(),
        val currentThermalState: ThermalState = ThermalState.NOMINAL,
        val wasCancelledByBackground: Boolean = false,
        val wasCompleted: Boolean = false,
        val testDuration: TestDuration = TestDuration.MINUTES_5,
        val testThreadingType: StressThreadingType = StressThreadingType.MULTI,
        val sessionId: Int? = null,
        val encryptionKey: String? = null,
        val recordedStamps: List<DeviceHammerStamp> = emptyList(),
        val batteryTemp: Float = 0f,
        val cpuTemp: Float = 0f,
        val thermalSensors: List<Pair<String, Float>> = emptyList(),
        val cpuFrequencies: List<CpuCoreFreq> = emptyList(),
        val initialBatteryLevel: Int = 0,
        val initialBatteryTemp: Float = 0f,
        val finalBatteryLevel: Int = 0,
        val finalBatteryTemp: Float = 0f,
        val currentBatteryLevel: Int = 0,
        val gpuImpact: Float = 0f
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class StressEngine(private val appContext: Context) : ViewModel(), DefaultLifecycleObserver {

    private val _state = MutableStateFlow(StressState())
    val state: StateFlow<StressState> = _state.asStateFlow()

    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                updateLiveTemperatures()
                delay(1000)
            }
        }
    }

    private fun updateLiveTemperatures() {
        val battTemp = getBatteryTemperature()
        val battPct = getBatteryPercentage()
        val zones = getSystemThermalZones()

        // Find CPU temp: Look for zones containing "cpu"
        val cpuZones = zones.filter { it.name.contains("cpu", ignoreCase = true) }
        val cpuTemp =
                if (cpuZones.isNotEmpty()) {
                    cpuZones.map { it.temp }.maxOrNull() ?: 0f
                } else {
                    zones
                            .firstOrNull {
                                it.name.contains("soc", ignoreCase = true) ||
                                        it.name.contains("tsens", ignoreCase = true)
                            }
                            ?.temp
                            ?: 0f
                }

        val sensorPairs = zones.map { Pair(it.name, it.temp) }
        val freqs = getCpuFrequencies()

        _state.update {
            it.copy(
                    batteryTemp = battTemp,
                    currentBatteryLevel = battPct,
                    cpuTemp = cpuTemp,
                    thermalSensors = sensorPairs,
                    cpuFrequencies = freqs
            )
        }
    }

    private fun getCpuFrequencies(): List<CpuCoreFreq> {
        val cpuDir = java.io.File("/sys/devices/system/cpu")
        if (!cpuDir.exists()) return emptyList()
        return (0 until coreCount).map { core ->
            val base = java.io.File(cpuDir, "cpu$core/cpufreq")
            fun readLong(name: String): Long =
                try { java.io.File(base, name).readText().trim().toLongOrNull() ?: 0L }
                catch (_: Exception) { 0L }
            CpuCoreFreq(
                core      = core,
                currentKHz = readLong("scaling_cur_freq"),
                maxKHz     = readLong("cpuinfo_max_freq")
            )
        }
    }

    private fun getBatteryTemperature(): Float {
        return try {
            val intent =
                    appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                temp.toFloat() / 10f
            } else 0f
        } catch (e: Exception) {
            0f
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val intent =
                    appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    ((level.toFloat() / scale.toFloat()) * 100f).toInt()
                } else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private data class TempZone(val name: String, val temp: Float)

    private fun getSystemThermalZones(): List<TempZone> {
        val zones = mutableListOf<TempZone>()
        try {
            val dir = java.io.File("/sys/class/thermal")
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isDirectory && file.name.startsWith("thermal_zone")) {
                            val typeFile = java.io.File(file, "type")
                            val tempFile = java.io.File(file, "temp")
                            if (typeFile.exists() && tempFile.exists()) {
                                try {
                                    val type = typeFile.readText().trim()
                                    val tempStr = tempFile.readText().trim()
                                    val tempRaw = tempStr.toFloatOrNull() ?: continue
                                    val temp =
                                            if (tempRaw > 1000f || tempRaw < -1000f) tempRaw / 1000f
                                            else tempRaw
                                    if (temp in -40f..150f) {
                                        zones.add(TempZone(type, temp))
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return zones
    }

    // Worker thread controls
    private val threadAlive = AtomicBoolean(false)
    private val counters: Array<AtomicLong> = Array(coreCount) { AtomicLong(0L) }
    private val prevCounterValues: LongArray = LongArray(coreCount)
    private val coreBaselines: DoubleArray = DoubleArray(coreCount) { 1.0 }
    private var overallBaseline: Double = 1.0
    private var statsSampleCount = 0

    private val gpuCounter = AtomicLong(0L)
    private var prevGpuCounter: Long = 0L
    private var gpuBaseline: Double = 1.0

    private var statsJob: Job? = null
    private var timerJob: Job? = null
    private var workerThreads: List<Thread> = emptyList()
    private var gpuThread: Thread? = null

    private fun startGpuWorker() {
        // CPU-based GPU stress: mobile GPUs can't preempt heavy fragment shaders,
        // which blocks Compose's RenderThread and freezes the UI on stop.
        // CPU math generates equivalent thermal load and exits instantly.
        gpuThread =
                Thread {
                    var a = 1.0f
                    var b = 2.0f
                    while (threadAlive.get()) {
                        var i = 0
                        while (i < 20_000) {
                            a = a * b + 0.0001f
                            b = b * a + 0.0002f
                            i++
                        }
                        gpuCounter.addAndGet(1L)
                    }
                    if (a + b == 0f) println("noop gpu: $a")
                }
                        .also {
                            it.priority = Thread.NORM_PRIORITY
                            it.start()
                        }
    }

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

        val activeThreadCount = if (threadingType == StressThreadingType.SINGLE) 1 else coreCount
        val initialCores =
                List(coreCount) { idx ->
                    if (threadingType == StressThreadingType.SINGLE && idx > 0) 0f else 100f
                }
        val startLevel = getBatteryPercentage()
        val startTemp = getBatteryTemperature()

        _state.update {
            it.copy(
                    isRunning = true,
                    elapsedSeconds = 0,
                    overallStability = 100f,
                    coreImpacts = initialCores,
                    chartPoints = listOf(StabilityPoint(0f, 100f)),
                    thermalEvents = emptyList(),
                    wasCancelledByBackground = false,
                    wasCompleted = false,
                    testDuration = duration,
                    testThreadingType = threadingType,
                    recordedStamps = emptyList(),
                    initialBatteryLevel = startLevel,
                    initialBatteryTemp = startTemp,
                    finalBatteryLevel = startLevel,
                    finalBatteryTemp = startTemp,
                    currentBatteryLevel = startLevel,
                    batteryTemp = startTemp
            )
        }

        // Add first thermal event
        addThermalEvent(_state.value.currentThermalState, 0f)

        for (i in 0 until coreCount) {
            counters[i].set(0L)
            prevCounterValues[i] = 0L
            coreBaselines[i] = 1.0
        }
        overallBaseline = 1.0
        gpuCounter.set(0L)
        prevGpuCounter = 0L
        gpuBaseline = 1.0
        statsSampleCount = 0

        threadAlive.set(true)
        workerThreads =
                (0 until activeThreadCount).map { idx ->
                    Thread {
                        var v1 = -6148914691236517206L // 0xAAAAAAAAAAAAAAAA as signed Long
                        var v2 = 6148914691236517205L // 0x5555555555555555 as signed Long
                        var v3 = 3689348814741910323L // 0x3333333333333333 as signed Long
                        var v4 = 8608480567731124087L // 0x7777777777777777 as signed Long

                        var f1 = 1.0000001
                        var f2 = 2.0000002
                        var f3 = 3.0000003
                        var f4 = 4.0000004

                        val l1Cache = LongArray(4096) { it.toLong() }
                        var cacheIdx = 0

                        while (threadAlive.get()) {
                            var i = 0
                            while (i < 50_000) {
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
                            counters[idx].addAndGet(50_000)
                        }
                        // Prevent optimizer from eliminating dead code
                        if (v1 + v2 + v3 + v4 + f1.toLong() == 0L) println("noop: $v1 $f1")
                    }
                            .also {
                                it.priority = Thread.NORM_PRIORITY
                                it.start()
                            }
                }

        startGpuWorker()

        statsJob =
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    while (isActive) {
                        delay(1000)
                        gatherStats()
                    }
                }

        timerJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(1000)
                        tickTimer()
                    }
                }
    }

    fun stopTest() {
        if (!_state.value.isRunning) return
        threadAlive.set(false)
        statsJob?.cancel()
        statsJob = null
        timerJob?.cancel()
        timerJob = null
        val endLevel = _state.value.currentBatteryLevel
        val endTemp = _state.value.batteryTemp
        _state.update {
            it.copy(isRunning = false, finalBatteryLevel = endLevel, finalBatteryTemp = endTemp)
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            workerThreads.forEach {
                try {
                    it.interrupt()
                } catch (_: Exception) {}
            }
            try {
                gpuThread?.interrupt()
            } catch (_: Exception) {}
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
                    elapsedSeconds = 0,
                    wasCompleted = false,
                    wasCancelledByBackground = false,
                    overallStability = 100f,
                    chartPoints = emptyList(),
                    recordedStamps = emptyList(),
                    coreImpacts = emptyList(),
                    thermalEvents = emptyList(),
                    initialBatteryLevel = 0,
                    initialBatteryTemp = 0f,
                    finalBatteryLevel = 0,
                    finalBatteryTemp = 0f
            )
        }
    }

    fun setThermalState(ts: ThermalState) {
        _state.update { it.copy(currentThermalState = ts) }
        if (_state.value.isRunning) {
            addThermalEvent(ts, _state.value.elapsedSeconds.toFloat())
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun gatherStats() {
        val coreSpeeds = DoubleArray(coreCount)
        var totalSpeed = 0.0

        for (i in 0 until coreCount) {
            val total = counters[i].get()
            val delta = (total - prevCounterValues[i]).toDouble()
            prevCounterValues[i] = total
            coreSpeeds[i] = delta
            totalSpeed += delta
        }

        // Calibrate baselines upward
        for (i in 0 until coreCount) {
            if (coreSpeeds[i] > coreBaselines[i]) coreBaselines[i] = coreSpeeds[i]
        }
        if (totalSpeed > overallBaseline) overallBaseline = totalSpeed

        val newImpacts =
                coreSpeeds.mapIndexed { i, spd ->
                    val impact = (spd / coreBaselines[i]) * 100.0
                    impact.coerceIn(0.0, 100.0).toFloat()
                }

        val newStability = ((totalSpeed / overallBaseline) * 100.0).coerceIn(0.0, 100.0).toFloat()

        statsSampleCount++
        val elapsedMs = statsSampleCount * 1000
        val scoreVal = totalSpeed.toLong()

        val thermalVal =
                when (_state.value.currentThermalState) {
                    ThermalState.NOMINAL -> 0
                    ThermalState.FAIR -> 1
                    ThermalState.SERIOUS -> 2
                    ThermalState.CRITICAL -> 3
                }
        val stamp = DeviceHammerStamp(elapsedMs, scoreVal, thermalVal)

        val gpuTotal = gpuCounter.get()
        val gpuDelta = (gpuTotal - prevGpuCounter).toDouble()
        prevGpuCounter = gpuTotal
        if (gpuDelta > gpuBaseline) gpuBaseline = gpuDelta
        val actualGpuImpact =
                if (gpuBaseline > 0)
                        ((gpuDelta / gpuBaseline) * 100.0).coerceIn(0.0, 100.0).toFloat()
                else 100f

        _state.update {
            it.copy(
                    coreImpacts = newImpacts,
                    gpuImpact = if (it.isRunning) actualGpuImpact else 0f,
                    overallStability = newStability,
                    recordedStamps = it.recordedStamps + stamp
            )
        }
    }

    private fun tickTimer() {
        val current = _state.value
        val newElapsed = current.elapsedSeconds + 1
        val newPoint = StabilityPoint(newElapsed.toFloat(), current.overallStability)
        val newPoints = current.chartPoints + newPoint

        val limitReached = current.testDuration.seconds?.let { newElapsed >= it } == true

        if (limitReached) {
            val endLevel = getBatteryPercentage()
            val endTemp = getBatteryTemperature()
            _state.update {
                it.copy(
                        elapsedSeconds = newElapsed,
                        chartPoints = newPoints,
                        wasCompleted = true,
                        finalBatteryLevel = endLevel,
                        finalBatteryTemp = endTemp
                )
            }
            stopTest()
        } else {
            _state.update { it.copy(elapsedSeconds = newElapsed, chartPoints = newPoints) }
        }
    }

    private fun addThermalEvent(state: ThermalState, time: Float) {
        val event = ThermalEvent(time, state)
        _state.update { it.copy(thermalEvents = it.thermalEvents + event) }
    }

    // ── Pre-test checks ───────────────────────────────────────────────────────

    fun isCharging(): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = appContext.registerReceiver(null, filter) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isBatterySaverOn(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

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

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model"
    }

    fun getThermalStateFromSystem(): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NOMINAL
            }
        }
        return ThermalState.NOMINAL
    }
}
