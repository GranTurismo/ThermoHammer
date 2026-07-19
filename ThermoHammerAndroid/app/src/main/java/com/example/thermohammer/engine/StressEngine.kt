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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ── Data Models ────────────────────────────────────────────────────────────────

enum class TestDuration(val displayName: String, val seconds: Int?) {
    MINUTES_5("5 Min", 5 * 60),
    MINUTES_15("15 Min", 15 * 60),
    MINUTES_30("30 Min", 30 * 60)
}

enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }

data class StabilityPoint(val time: Float, val score: Float)

data class ThermalEvent(
    val time: Float,
    val state: ThermalState
) {
    val name: String get() = when (state) {
        ThermalState.NOMINAL  -> "NOMINAL"
        ThermalState.FAIR     -> "FAIR"
        ThermalState.SERIOUS  -> "SERIOUS"
        ThermalState.CRITICAL -> "CRITICAL"
    }
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
    val sessionId: Int? = null,
    val encryptionKey: String? = null,
    val recordedStamps: List<DeviceHammerStamp> = emptyList(),
    val batteryTemp: Float = 0f,
    val cpuTemp: Float = 0f,
    val thermalSensors: List<Pair<String, Float>> = emptyList()
)

// ── ViewModel ──────────────────────────────────────────────────────────────────

class StressEngine(private val appContext: Context) : ViewModel(), DefaultLifecycleObserver {

    private val _state = MutableStateFlow(StressState())
    val state: StateFlow<StressState> = _state.asStateFlow()

    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    init {
        viewModelScope.launch {
            while (isActive) {
                updateLiveTemperatures()
                delay(1000)
            }
        }
    }

    private fun updateLiveTemperatures() {
        val battTemp = getBatteryTemperature()
        val zones = getSystemThermalZones()
        
        // Find CPU temp: Look for zones containing "cpu"
        val cpuZones = zones.filter { it.name.contains("cpu", ignoreCase = true) }
        val cpuTemp = if (cpuZones.isNotEmpty()) {
            cpuZones.map { it.temp }.maxOrNull() ?: 0f
        } else {
            zones.firstOrNull { it.name.contains("soc", ignoreCase = true) || it.name.contains("tsens", ignoreCase = true) }?.temp ?: 0f
        }
        
        val sensorPairs = zones.map { Pair(it.name, it.temp) }
        
        _state.update {
            it.copy(
                batteryTemp = battTemp,
                cpuTemp = cpuTemp,
                thermalSensors = sensorPairs
            )
        }
    }

    private fun getBatteryTemperature(): Float {
        return try {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                temp.toFloat() / 10f
            } else 0f
        } catch (e: Exception) {
            0f
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
                                    val temp = if (tempRaw > 1000f || tempRaw < -1000f) tempRaw / 1000f else tempRaw
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

    private var statsJob: Job? = null
    private var timerJob: Job? = null
    private var workerThreads: List<Thread> = emptyList()

    // ── Lifecycle (background cancel) ────────────────────────────────────────

    override fun onStop(owner: LifecycleOwner) {
        if (_state.value.isRunning) {
            _state.update { it.copy(wasCancelledByBackground = true) }
            stopTest()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startTest(duration: TestDuration) {
        if (_state.value.isRunning) return

        val initialCores = List(coreCount) { 100f }
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
                recordedStamps = emptyList()
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
        statsSampleCount = 0

        threadAlive.set(true)
        workerThreads = (0 until coreCount).map { idx ->
            Thread {
                var a = -6148914691236517206L // 0xAAAAAAAAAAAAAAAA as signed Long
                var b = 6148914691236517205L  // 0x5555555555555555 as signed Long
                while (threadAlive.get()) {
                    repeat(50_000) {
                        a = a xor b
                        b++
                        a *= 3
                        b = b xor a
                        a += 7
                    }
                    counters[idx].addAndGet(50_000)
                }
                // Prevent optimizer from eliminating dead code
                if (a + b == 0L) println("noop: $a")
            }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
        }

        statsJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                gatherStats()
            }
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                tickTimer()
            }
        }
    }

    fun stopTest() {
        if (!_state.value.isRunning) return
        threadAlive.set(false)
        statsJob?.cancel(); statsJob = null
        timerJob?.cancel(); timerJob = null
        _state.update { it.copy(isRunning = false) }
    }

    fun setSession(id: Int, key: String) {
        _state.update { it.copy(sessionId = id, encryptionKey = key) }
    }

    fun clearSession() {
        _state.update { it.copy(sessionId = null, encryptionKey = null) }
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

        val newImpacts = coreSpeeds.mapIndexed { i, spd ->
            val impact = (spd / coreBaselines[i]) * 100.0
            impact.coerceIn(0.0, 100.0).toFloat()
        }

        val newStability = ((totalSpeed / overallBaseline) * 100.0).coerceIn(0.0, 100.0).toFloat()

        statsSampleCount++
        val elapsedMs = statsSampleCount * 250
        val scoreVal = (totalSpeed * 4).toLong()

        val thermalVal = when (_state.value.currentThermalState) {
            ThermalState.NOMINAL  -> 0
            ThermalState.FAIR     -> 1
            ThermalState.SERIOUS  -> 2
            ThermalState.CRITICAL -> 3
        }
        val stamp = DeviceHammerStamp(elapsedMs, scoreVal, thermalVal)

        _state.update {
            it.copy(
                coreImpacts = newImpacts,
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
            _state.update {
                it.copy(
                    elapsedSeconds = newElapsed,
                    chartPoints = newPoints,
                    wasCompleted = true
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
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    fun getThermalStateFromSystem(): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE       -> ThermalState.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT      -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_MODERATE   -> ThermalState.FAIR
                PowerManager.THERMAL_STATUS_SEVERE     -> ThermalState.SERIOUS
                PowerManager.THERMAL_STATUS_CRITICAL   -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY  -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_SHUTDOWN   -> ThermalState.CRITICAL
                else -> ThermalState.NOMINAL
            }
        }
        return ThermalState.NOMINAL
    }
}
