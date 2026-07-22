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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.*
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.HammerPayload
import com.example.thermohammer.network.ThermoHasher
import com.example.thermohammer.ui.components.*
import com.example.thermohammer.ui.overlays.*
import androidx.compose.ui.platform.LocalContext
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
                            val hash = ThermoHasher.computeHash(state.encryptionKey!!, stamps)
                            val payload = HammerPayload(
                                stamps = stamps,
                                type = durationType,
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "THERMOHAMMER",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                )
                // Online/Offline pill
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, if (isNetworkConnected) Color(0xFF33CC66).copy(alpha = 0.15f) else Color(0xFFF2C94C).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isNetworkConnected) Color(0xFF33CC66) else Color(0xFFF2C94C))
                    )
                    Text(
                        if (isNetworkConnected) "ONLINE" else "OFFLINE",
                        style = TextStyle(
                            color = if (isNetworkConnected) Color(0xFF33CC66) else Color(0xFFF2C94C),
                            fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black
                        )
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Android CPU Stress & Throttling Diagnostic",
                style = TextStyle(
                    color = if (state.isRunning) Color(0xFFF2994A) else Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            )
        }
        // Pulsing red dot when running
        if (state.isRunning) {
            val pulseAnim = rememberInfiniteTransition(label = "pulse")
            val scale by pulseAnim.animateFloat(1f, 2.5f, infiniteRepeatable(tween(1200, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "pulse_scale")
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).scale(scale).clip(CircleShape).background(Color(0xFFEB5757).copy(alpha = 0.3f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEB5757)))
            }
        }
    }
}

@Composable
internal fun StatsPanel(state: StressState) {
    val thermalColor = com.example.thermohammer.ui.components.thermalColor(state.currentThermalState)
    val thermalName = com.example.thermohammer.ui.components.thermalName(state.currentThermalState)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val displaySeconds = if (state.isRunning) {
                val totalSeconds = state.testDuration.seconds ?: 300
                maxOf(0, totalSeconds - state.elapsedSeconds)
            } else {
                state.elapsedSeconds
            }
            StatCard(title = "TEST DURATION", value = "%02d:%02d".format(displaySeconds / 60, displaySeconds % 60), modifier = Modifier.weight(1f))
            StatCard(
                title = "STABILITY SCORE",
                value = "%.0f%%".format(state.overallStability),
                valueColor = stabilityColor(state.overallStability),
                modifier = Modifier.weight(1f)
            )
        }
        // Thermal bar
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🌡 ", fontSize = 14.sp, color = thermalColor)
            Text("THERMAL STATE: $thermalName", style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text(
                if (state.isRunning) "STRESS ACTIVE" else "STRESS INACTIVE",
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (state.isRunning) Color(0xFFF2994A).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(
                    color = if (state.isRunning) Color(0xFFF2994A) else Color.White.copy(alpha = 0.4f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black
                )
            )
        }
    }
}

@Composable
internal fun StatCard(title: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.White) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(title, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Text(value, style = TextStyle(color = valueColor, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
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
            style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.02f))
                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(18.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(TestDuration.MINUTES_5, TestDuration.MINUTES_15, TestDuration.MINUTES_30).forEach { duration ->
                val isSelected = selected == duration
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable { onSelect(duration) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        duration.displayName,
                        style = TextStyle(
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun ControlButton(state: StressState, onStart: () -> Unit, onStop: () -> Unit) {
    val isRunning = state.isRunning
    val gradient = if (isRunning)
        Brush.verticalGradient(listOf(Color(0xFFD93025), Color(0xFFB71C1C)))
    else
        Brush.verticalGradient(listOf(Color(0xFF0D84FF), Color(0xFF005AC1)))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(gradient)
            .clickable { if (isRunning) onStop() else onStart() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isRunning) "◼" else "▶", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(
                if (isRunning) "STOP STRESS TEST" else "INITIATE STRESS TEST",
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            )
        }
    }
}
