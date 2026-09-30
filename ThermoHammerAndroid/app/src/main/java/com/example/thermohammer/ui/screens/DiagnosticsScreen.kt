package com.example.thermohammer.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.*
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.HammerPayload
import com.example.thermohammer.network.ThermoHasher
import com.example.thermohammer.network.toWire
import com.example.thermohammer.ui.components.*
import com.example.thermohammer.ui.overlays.*
import com.example.thermohammer.ui.theme.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(
    engine: StressEngine,
    isNetworkConnected: Boolean
) {
    val state by engine.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Overlay states
    var showPreTest by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showBackgroundAborted by remember { mutableStateOf(false) }
    var showManualCancelled by remember { mutableStateOf(false) }
    var showServerInit by remember { mutableStateOf(false) }
    var showServerError by remember { mutableStateOf(false) }
    var serverErrorMsg by remember { mutableStateOf("") }

    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }

    // React to test stopping
    LaunchedEffect(state.isRunning) {
        if (!state.isRunning && state.elapsedSeconds > 0) {
            isSubmitting = false; submitSuccess = null; submitError = null
            when {
                state.wasCancelledByBackground -> showBackgroundAborted = true
                !state.wasCompleted -> showManualCancelled = true
                else -> showSummary = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Content
        Column(Modifier.fillMaxSize()) {
            ScrollableContent(
                state = state,
                isNetworkConnected = isNetworkConnected,
                onStartPressed = { showPreTest = true },
                onStopPressed = { engine.stopTest() },
                modifier = Modifier.weight(1f)
            )
        }

        // Overlays
        if (showPreTest) {
            PreTestOverlay(
                isCharging = engine.isCharging(),
                isBatterySaver = engine.isBatterySaverOn(),
                isCellularActive = engine.isCellularActive(),
                onCancel = { showPreTest = false },
                onProceed = {
                    showPreTest = false
                    if (isNetworkConnected) {
                        showServerInit = true
                        coroutineScope.launch {
                            try {
                                val session = ApiClient.api.createSession()
                                engine.setSession(session.id, session.encryptionKey)
                                showServerInit = false
                                engine.startTest(state.testDuration)
                            } catch (e: Exception) {
                                showServerInit = false
                                serverErrorMsg = e.message ?: "Unknown error"
                                showServerError = true
                            }
                        }
                    } else {
                        engine.clearSession()
                        engine.startTest(state.testDuration)
                    }
                }
            )
        }

        if (showSummary) {
            val stamps = state.recordedStamps
            val maxScore = stamps.maxOfOrNull { it.score.toDouble() } ?: 1.0
            val secondHalfStart = stamps.size / 2
            val secondHalfStamps = stamps.subList(secondHalfStart, stamps.size)
            val avgSecondHalf = if (secondHalfStamps.isNotEmpty()) secondHalfStamps.map { it.score.toDouble() }.average() else 0.0
            val finalStab = if (maxScore > 0) ((avgSecondHalf / maxScore) * 100.0).toFloat() else 100f

            val minStab = state.chartPoints.minOfOrNull { it.score } ?: state.overallStability
            val worstThermal = state.thermalEvents.maxByOrNull { it.state.ordinal }?.state ?: ThermalState.NOMINAL
            SummaryOverlay(
                duration = state.elapsedSeconds,
                minStability = minStab,
                finalStability = finalStab,
                worstThermal = worstThermal,
                initialBatteryLevel = state.initialBatteryLevel,
                finalBatteryLevel = state.finalBatteryLevel,
                initialBatteryTemp = state.initialBatteryTemp,
                finalBatteryTemp = state.finalBatteryTemp,
                hasSession = state.sessionId != null,
                isNetworkConnected = isNetworkConnected,
                isSubmitting = isSubmitting,
                submitSuccess = submitSuccess,
                submitError = submitError,
                onSubmit = {
                    isSubmitting = true
                    coroutineScope.launch {
                        try {
                            val durationType = when (state.testDuration) {
                                TestDuration.MINUTES_5  -> 0
                                TestDuration.MINUTES_15 -> 1
                                TestDuration.MINUTES_30 -> 2
                            }
                            val hash = ThermoHasher.computeHash(state.encryptionKey!!, stamps.toWire())
                            val payload = HammerPayload(
                                stamps = stamps.toWire(),
                                type = durationType,
                                testThreadingType = state.testThreadingType.value,
                                deviceManufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() },
                                deviceModel = engine.getDeviceModel(),
                                os = 2, // Android
                                osVersion = engine.getAndroidVersion(),
                                sessionId = state.sessionId!!,
                                hash = hash
                            )
                            ApiClient.api.submitScore(payload)
                            isSubmitting = false
                            submitSuccess = "SUBMITTED TO LEADERBOARD!"
                        } catch (e: Exception) {
                            isSubmitting = false
                            submitError = e.message ?: "Submission failed"
                        }
                    }
                },
                onSavePending = {
                    try {
                        val maxScore = stamps.maxOfOrNull { it.score.toDouble() } ?: 1.0
                        val secondHalfStart = stamps.size / 2
                        val secondHalfStamps = stamps.subList(secondHalfStart, stamps.size)
                        val avgSecondHalf = if (secondHalfStamps.isNotEmpty()) secondHalfStamps.map { it.score.toDouble() }.average() else 0.0
                        val finalStab = if (maxScore > 0) ((avgSecondHalf / maxScore) * 100.0).toFloat() else 100f

                        val durationType = when (state.testDuration) {
                            TestDuration.MINUTES_5 -> 0; TestDuration.MINUTES_15 -> 1; TestDuration.MINUTES_30 -> 2
                        }
                        val pending = com.example.thermohammer.data.PendingTestResult(
                            id = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            durationSeconds = state.elapsedSeconds,
                            testDurationType = durationType,
                            testThreadingType = state.testThreadingType.value,
                            minStability = minStab,
                            finalStability = finalStab,
                            worstThermalState = worstThermal.ordinal,
                            stamps = stamps,
                            deviceModel = engine.getDeviceModel(),
                            deviceManufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() },
                            osVersion = engine.getAndroidVersion(),
                            sessionId = state.sessionId ?: 0,
                            encryptionKey = state.encryptionKey ?: ""
                        )
                        com.example.thermohammer.data.PendingResultStore(context).saveResult(pending)
                        submitSuccess = "SAVED TO PENDING RESULTS!"
                    } catch (e: Exception) {
                        submitError = "Failed to save pending result"
                    }
                },
                onDismiss = { showSummary = false }
            )
        }

        if (showBackgroundAborted) BackgroundAbortedOverlay { showBackgroundAborted = false }
        if (showManualCancelled) ManualCancelledOverlay { showManualCancelled = false }
        if (showServerInit) ServerInitOverlay()
        if (showServerError) ServerErrorOverlay(
            errorMessage = serverErrorMsg,
            onCancel = { showServerError = false },
            onRunOffline = {
                showServerError = false
                engine.clearSession()
                engine.startTest(state.testDuration)
            }
        )
    }
}

@Composable
private fun ScrollableContent(
    state: StressState,
    isNetworkConnected: Boolean,
    onStartPressed: () -> Unit,
    onStopPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header
        AppHeader(state, isNetworkConnected)
        // Stats
        StatsPanel(state)
        // Duration picker (only when idle)
        if (!state.isRunning) DurationPicker(state)
        // Control button
        ControlButton(state, onStartPressed, onStopPressed)
        // Chart
        StabilityChart(points = state.chartPoints, events = state.thermalEvents)
        // Core meters
        if (state.coreImpacts.isNotEmpty()) CoreStatusView(state.coreImpacts)
        ThermalMonitoringPanel(state)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun AppHeader(state: StressState, isNetworkConnected: Boolean) {
    val thermalCol = thermalStateColor(state.currentThermalState)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⌖", color = Forge.phaseMeasured, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(
                    "THERMOHAMMER",
                    style = TextStyle(
                        color = Forge.ink0,
                        fontSize = 16.sp,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 3.sp
                    )
                )
                // Online/Offline pill
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Forge.surface)
                        .border(
                            1.dp,
                            (if (isNetworkConnected) Forge.phaseMeasured else Ramp.at(0.5f)).copy(alpha = 0.25f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isNetworkConnected) Forge.phaseMeasured else Ramp.at(0.5f))
                    )
                    Text(
                        if (isNetworkConnected) "ONLINE" else "OFFLINE",
                        style = ThermoType.label(7f, if (isNetworkConnected) Forge.phaseMeasured else Ramp.at(0.5f))
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "cpu stress & thermal-efficiency instrument",
                style = ThermoType.annotation(8f, if (state.isRunning) phaseColor(state.phase) else Forge.ink2)
            )
        }
        // Live thermal status chip — the instrument's always-on readout
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(thermalCol))
                Text(
                    thermalStateName(state.currentThermalState),
                    style = ThermoType.label(9f, thermalCol)
                )
            }
            Spacer(Modifier.height(3.dp))
            TickerText(
                value = state.cpuTemp,
                format = { if (it > 0f) "%.1f°C".format(it) else "--°C" },
                color = if (state.cpuTemp > 0f) Ramp.forTemp(state.cpuTemp) else Forge.ink2,
                size = 11f
            )
        }
    }
}

@Composable
internal fun StatsPanel(state: StressState) {
    val thermalCol = thermalStateColor(state.currentThermalState)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val durationText = when {
                !state.isRunning -> "%02d:%02d".format(state.elapsedSeconds / 60, state.elapsedSeconds % 60)
                state.phase == RunPhase.MEASURED -> {
                    val totalSeconds = state.testDuration.seconds ?: 300
                    val remaining = maxOf(0, totalSeconds - state.elapsedSeconds)
                    "%02d:%02d".format(remaining / 60, remaining % 60)
                }
                else -> state.phase.displayName
            }
            StatCard(title = "TEST TIME", value = durationText, modifier = Modifier.weight(1f))
            StatCard(
                title = "DELIVERED",
                value = "%.0f%%".format(state.overallStability),
                valueColor = Ramp.forCapacity(state.overallStability),
                modifier = Modifier.weight(1f)
            )
        }
        // Thermal bar
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Forge.raised)
                .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(thermalCol))
            Spacer(Modifier.width(10.dp))
            Text("THERMAL: ${thermalStateName(state.currentThermalState)}", style = ThermoType.data(11f, Forge.ink0, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text(
                if (state.isRunning) state.phase.displayName else "STANDBY",
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(phaseColor(state.phase).copy(alpha = if (state.isRunning) 0.18f else 0.08f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = ThermoType.label(8f, if (state.isRunning) phaseColor(state.phase) else Forge.ink2)
            )
        }
    }
}

@Composable
internal fun StatCard(title: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Forge.ink0) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised)
            .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, style = ThermoType.label(8.5f))
        Spacer(Modifier.height(6.dp))
        Text(value, style = ThermoType.data(20f, valueColor, FontWeight.Bold))
    }
}

@Composable
private fun DurationPicker(state: StressState) {
    // We need to be able to update duration in engine; use a local state for selection
    // The engine stores testDuration in state; parent can pass in a lambda
    // For now just display — duration is passed via startTest when proceed is pressed
}

@Composable
fun DurationPicker(
    selected: TestDuration,
    onSelect: (TestDuration) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "TARGET DURATION",
            style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Forge.surface)
                .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(TestDuration.MINUTES_5, TestDuration.MINUTES_15, TestDuration.MINUTES_30).forEach { duration ->
                val isSelected = selected == duration
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Forge.phaseMeasured else Forge.raised)
                        .border(1.dp, if (isSelected) Color.Transparent else Forge.hairline, RoundedCornerShape(10.dp))
                        .clickable { onSelect(duration) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        duration.displayName,
                        style = TextStyle(
                            color = if (isSelected) Forge.bg else Forge.ink0,
                            fontSize = 12.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ModePicker(
    selected: StressMode,
    onSelect: (StressMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "STRESS CHANNEL",
            style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Forge.surface)
                .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StressMode.entries.forEach { mode ->
                val isSelected = selected == mode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Forge.phaseCalibration else Forge.raised)
                        .border(1.dp, if (isSelected) Color.Transparent else Forge.hairline, RoundedCornerShape(10.dp))
                        .clickable { onSelect(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.displayName,
                        style = TextStyle(
                            color = if (isSelected) Forge.bg else Forge.ink0,
                            fontSize = 11.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ThreadingPicker(
    selected: StressThreadingType,
    onSelect: (StressThreadingType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            "THREADING MODE",
            style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Forge.surface)
                .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(StressThreadingType.MULTI, StressThreadingType.SINGLE).forEach { type ->
                val isSelected = selected == type
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Forge.phaseMeasured else Forge.raised)
                        .border(1.dp, if (isSelected) Color.Transparent else Forge.hairline, RoundedCornerShape(10.dp))
                        .clickable { onSelect(type) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (type == StressThreadingType.MULTI) "RECOMMENDED" else " ",
                            style = TextStyle(
                                color = if (isSelected) Forge.bg.copy(alpha = 0.7f) else Forge.phaseMeasured.copy(alpha = 0.7f),
                                fontSize = 7.sp,
                                fontFamily = InstrumentMono,
                                fontWeight = FontWeight.Black
                            ),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                        Text(
                            type.displayName,
                            style = TextStyle(
                                color = if (isSelected) Forge.bg else Forge.ink0,
                                fontSize = 12.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ControlButton(state: StressState, onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning = state.isRunning
    if (!isRunning) {
        // INITIATE — ramp-teal edge-to-edge CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Forge.phaseMeasured, Ramp.at(0.25f))))
                .clickable(onClick = onStart)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("▸", color = Forge.bg, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(
                    "INITIATE STRESS TEST",
                    style = TextStyle(color = Forge.bg, fontSize = 13.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                )
            }
        }
        return
    }

    // HOLD TO STOP — press-and-hold ring prevents accidental kills mid-run
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val holdProgress = remember { Animatable(0f) }
    var holdFired by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed && !holdFired) {
            holdProgress.animateTo(
                1f,
                animationSpec = tween(600, easing = LinearEasing)
            )
            if (!holdFired) { holdFired = true; onStop() }
        } else if (!pressed) {
            holdProgress.snapTo(0f)
            if (holdFired) holdFired = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised)
            .border(1.dp, Ramp.at(0.85f).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null) {}
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)) {
                    drawCircle(Ramp.at(0.85f).copy(alpha = 0.25f), style = Stroke(2.5f))
                    drawArc(
                        Ramp.at(0.85f), -90f, 360f * holdProgress.value, false,
                        style = Stroke(2.5f, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                if (pressed && !holdFired) "HOLDING…" else "HOLD TO ABORT",
                style = TextStyle(color = Ramp.at(0.85f), fontSize = 12.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            )
        }
    }
}
