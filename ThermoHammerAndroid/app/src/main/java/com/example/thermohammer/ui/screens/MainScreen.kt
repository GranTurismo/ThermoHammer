package com.example.thermohammer.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressEngineFactory
import com.example.thermohammer.engine.TestDuration
import com.example.thermohammer.ui.components.CoreStatusView
import com.example.thermohammer.ui.components.StabilityChart
import com.example.thermohammer.ui.components.ThermalMonitoringPanel
import kotlinx.coroutines.launch

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val engine: StressEngine = viewModel(factory = StressEngineFactory(context))
    val state by engine.state.collectAsState()

    val isNetworkConnected = remember { mutableStateOf(checkNetwork(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            isNetworkConnected.value = checkNetwork(context)
            kotlinx.coroutines.delay(3000)
        }
    }

    // Track thermal state periodically
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            while (state.isRunning) {
                engine.setThermalState(engine.getThermalStateFromSystem())
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    // Local duration & threading state
    var selectedDuration by remember { mutableStateOf(TestDuration.MINUTES_5) }
    var selectedThreadingType by remember { mutableStateOf(com.example.thermohammer.engine.StressThreadingType.MULTI) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0F14))
    ) {
        Column(Modifier.fillMaxSize()) {
            // Main content
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> DiagnosticsScreenWrapper(
                        engine = engine,
                        isNetworkConnected = isNetworkConnected.value,
                        selectedDuration = selectedDuration,
                        selectedThreadingType = selectedThreadingType,
                        onDurationChange = { selectedDuration = it },
                        onThreadingChange = { selectedThreadingType = it },
                        onNavigateToLeaderboard = { selectedTab = 1 }
                    )
                    1 -> LeaderboardScreen(isNetworkConnected.value)
                }
            }
            // Tab bar (hidden when running)
            if (!state.isRunning) {
                BottomTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }
        }
    }
}

@Composable
private fun DiagnosticsScreenWrapper(
    engine: StressEngine,
    isNetworkConnected: Boolean,
    selectedDuration: TestDuration,
    selectedThreadingType: com.example.thermohammer.engine.StressThreadingType,
    onDurationChange: (TestDuration) -> Unit,
    onThreadingChange: (com.example.thermohammer.engine.StressThreadingType) -> Unit,
    onNavigateToLeaderboard: () -> Unit
) {
    val state by engine.state.collectAsState()
    var showPreTest by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Overlays states
    var showSummary by remember { mutableStateOf(false) }
    var showBackgroundAborted by remember { mutableStateOf(false) }
    var showManualCancelled by remember { mutableStateOf(false) }
    var showServerInit by remember { mutableStateOf(false) }
    var showServerError by remember { mutableStateOf(false) }
    var serverErrorMsg by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf<String?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var currentPendingResultId by remember { mutableStateOf<String?>(null) }
    var showConnectionRequest by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val store = remember { com.example.thermohammer.data.PendingResultStore(context) }
    var pendingCount by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            if (state.wasCompleted || state.wasCancelledByBackground || state.elapsedSeconds > 0) {
                engine.resetTestResult()
            }
        }
    }

    LaunchedEffect(showSummary, state.isRunning) {
        pendingCount = store.getAllResults().size
    }

    LaunchedEffect(state.isRunning) {
        if (!state.isRunning && state.elapsedSeconds > 0) {
            isSubmitting = false; submitSuccess = null; submitError = null
            if (state.wasCompleted) {
                // Automatically save run to pending results immediately!
                try {
                    val stamps = state.recordedStamps
                    val maxScore = stamps.maxOfOrNull { it.score.toDouble() } ?: 1.0
                    val secondHalfStart = stamps.size / 2
                    val secondHalfStamps = stamps.subList(secondHalfStart, stamps.size)
                    val avgSecondHalf = if (secondHalfStamps.isNotEmpty()) secondHalfStamps.map { it.score.toDouble() }.average() else 0.0
                    val finalStab = if (maxScore > 0) ((avgSecondHalf / maxScore) * 100.0).toFloat() else 100f
                    
                    val minStab = state.chartPoints.minOfOrNull { it.score } ?: state.overallStability
                    val worstThermal = state.thermalEvents.maxByOrNull { it.state.ordinal }?.state ?: com.example.thermohammer.engine.ThermalState.NOMINAL
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
                        sessionId = 0,
                        encryptionKey = ""
                    )
                    com.example.thermohammer.data.PendingResultStore(context).saveResult(pending)
                    currentPendingResultId = pending.id
                } catch (e: Exception) {
                    // Ignore background errors
                }
                
                if (isNetworkConnected) {
                    showSummary = true
                } else {
                    showConnectionRequest = true
                }
            } else if (state.wasCancelledByBackground) {
                showBackgroundAborted = true
            } else {
                showManualCancelled = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Scrollable content
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppHeader(state, isNetworkConnected)
            if (pendingCount > 0 && !state.isRunning) {
                PendingRunsBanner(pendingCount, onNavigateToLeaderboard)
            }
            StatsPanel(state)
            if (!state.isRunning) {
                DurationPicker(selected = selectedDuration, onSelect = onDurationChange)
                ThreadingPicker(selected = selectedThreadingType, onSelect = onThreadingChange)
            }
            ControlButton(state, onStart = { showPreTest = true }, onStop = { engine.stopTest() })
            StabilityChart(points = state.chartPoints, events = state.thermalEvents)
            if (state.coreImpacts.isNotEmpty()) CoreStatusView(state.coreImpacts)
            ThermalMonitoringPanel(state)
            Spacer(Modifier.height(16.dp))
        }

        // Overlays
        if (showPreTest) {
            com.example.thermohammer.ui.overlays.PreTestOverlay(
                isCharging = engine.isCharging(),
                isBatterySaver = engine.isBatterySaverOn(),
                isCellularActive = engine.isCellularActive(),
                onCancel = { showPreTest = false },
                onProceed = {
                    showPreTest = false
                    engine.clearSession()
                    engine.startTest(selectedDuration, selectedThreadingType)
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
            val worstThermal = state.thermalEvents.maxByOrNull { it.state.ordinal }?.state ?: com.example.thermohammer.engine.ThermalState.NOMINAL
            com.example.thermohammer.ui.overlays.SummaryOverlay(
                duration = state.elapsedSeconds,
                minStability = minStab,
                finalStability = finalStab,
                worstThermal = worstThermal,
                initialBatteryLevel = state.initialBatteryLevel,
                finalBatteryLevel = state.finalBatteryLevel,
                initialBatteryTemp = state.initialBatteryTemp,
                finalBatteryTemp = state.finalBatteryTemp,
                hasSession = isNetworkConnected,
                isNetworkConnected = isNetworkConnected,
                isSubmitting = isSubmitting,
                submitSuccess = submitSuccess,
                submitError = submitError,
                onSubmit = {
                    isSubmitting = true
                    coroutineScope.launch {
                        try {
                            val durationType = when (state.testDuration) {
                                TestDuration.MINUTES_5 -> 0; TestDuration.MINUTES_15 -> 1; TestDuration.MINUTES_30 -> 2
                            }
                            // Create session on-the-fly upon submit tap
                            val session = com.example.thermohammer.network.ApiClient.api.createSession()
                            val hash = com.example.thermohammer.network.ThermoHasher.computeHash(session.encryptionKey, stamps)
                            val payload = com.example.thermohammer.network.HammerPayload(
                                stamps = stamps, type = durationType,
                                testThreadingType = state.testThreadingType.value,
                                deviceManufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() },
                                deviceModel = engine.getDeviceModel(), os = 2,
                                osVersion = engine.getAndroidVersion(),
                                sessionId = session.id, hash = hash
                            )
                            com.example.thermohammer.network.ApiClient.api.submitScore(payload)
                            
                            // Clear from auto-saved pending results on successful submit
                            currentPendingResultId?.let { pid ->
                                com.example.thermohammer.data.PendingResultStore(context).deleteResult(pid)
                            }
                            isSubmitting = false; submitSuccess = "SUBMITTED TO LEADERBOARD!"
                        } catch (e: Exception) {
                            isSubmitting = false; submitError = e.message ?: "Submission failed"
                        }
                    }
                },
                onSavePending = {
                    submitSuccess = "SAVED TO PENDING RESULTS!"
                },
                onDismiss = { showSummary = false; engine.resetTestResult() }
            )
        }
        if (showConnectionRequest) {
            com.example.thermohammer.ui.overlays.ConnectionRequestOverlay(
                onTurnedOn = {
                    showConnectionRequest = false
                    submitSuccess = null
                    submitError = null
                    showSummary = true
                },
                onSubmitLater = {
                    showConnectionRequest = false
                    showSummary = true
                }
            )
        }
        if (showBackgroundAborted) com.example.thermohammer.ui.overlays.BackgroundAbortedOverlay { showBackgroundAborted = false; engine.resetTestResult() }
        if (showManualCancelled) com.example.thermohammer.ui.overlays.ManualCancelledOverlay { showManualCancelled = false; engine.resetTestResult() }
        if (showServerInit) com.example.thermohammer.ui.overlays.ServerInitOverlay()
        if (showServerError) com.example.thermohammer.ui.overlays.ServerErrorOverlay(
            errorMessage = serverErrorMsg,
            onCancel = { showServerError = false },
            onRunOffline = { showServerError = false; engine.clearSession(); engine.startTest(selectedDuration) }
        )
    }
}

@Composable
fun BottomTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf("⬤  Diagnostics" to 0, "♛  Leaderboard" to 1)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414).copy(alpha = 0.92f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { (label, idx) ->
            val isSelected = selectedTab == idx
            val color by animateColorAsState(
                if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                animationSpec = tween(300), label = "tab_color_$idx"
            )
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onTabSelected(idx) }
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    style = TextStyle(color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                )
            }
        }
    }
}


private fun checkNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun PendingRunsBanner(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141414).copy(alpha = 0.6f))
            .border(1.dp, Color(0xFFF2C94C).copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠️", fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "UNSUBMITTED RESULTS DETECTED",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "You have $count locally saved test run${if (count > 1) "s" else ""}. Tap here to submit to the leaderboard.",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 8.sp
                )
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "➔", 
            color = Color.White.copy(alpha = 0.3f), 
            fontSize = 12.sp, 
            fontWeight = FontWeight.Bold
        )
    }
}
