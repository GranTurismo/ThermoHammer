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
    // Local duration state
    var selectedDuration by remember { mutableStateOf(TestDuration.MINUTES_5) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0F14))
    ) {
        Column(Modifier.fillMaxSize()) {
            // Main content
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> DiagnosticsScreenWrapper(engine, isNetworkConnected.value, selectedDuration, onDurationChange = { selectedDuration = it })
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
    onDurationChange: (TestDuration) -> Unit
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
            StatsPanel(state)
            if (!state.isRunning) {
                DurationPicker(selected = selectedDuration, onSelect = onDurationChange)
            }
            ControlButton(state, onStart = { showPreTest = true }, onStop = { engine.stopTest() })
            StabilityChart(points = state.chartPoints, events = state.thermalEvents)
            if (state.coreImpacts.isNotEmpty()) CoreStatusView(state.coreImpacts)
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
                    if (isNetworkConnected) {
                        showServerInit = true
                        coroutineScope.launch {
                            try {
                                val session = com.example.thermohammer.network.ApiClient.api.createSession()
                                engine.setSession(session.id, session.encryptionKey)
                                showServerInit = false
                                engine.startTest(selectedDuration)
                            } catch (e: Exception) {
                                showServerInit = false
                                serverErrorMsg = e.message ?: "Unknown error"
                                showServerError = true
                            }
                        }
                    } else {
                        engine.clearSession()
                        engine.startTest(selectedDuration)
                    }
                }
            )
        }

        if (showSummary) {
            val stamps = state.recordedStamps
            val minStab = state.chartPoints.minOfOrNull { it.score } ?: state.overallStability
            val worstThermal = state.thermalEvents.maxByOrNull { it.state.ordinal }?.state ?: com.example.thermohammer.engine.ThermalState.NOMINAL
            com.example.thermohammer.ui.overlays.SummaryOverlay(
                duration = state.elapsedSeconds,
                minStability = minStab,
                finalStability = state.overallStability,
                worstThermal = worstThermal,
                hasSession = state.sessionId != null,
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
                            val hash = com.example.thermohammer.network.ThermoHasher.computeHash(state.encryptionKey!!, stamps)
                            val payload = com.example.thermohammer.network.HammerPayload(
                                stamps = stamps, type = durationType,
                                deviceManufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() },
                                deviceModel = engine.getDeviceModel(), os = 2,
                                osVersion = engine.getAndroidVersion(),
                                sessionId = state.sessionId!!, hash = hash
                            )
                            com.example.thermohammer.network.ApiClient.api.submitScore(payload)
                            isSubmitting = false; submitSuccess = "SUBMITTED TO LEADERBOARD!"
                        } catch (e: Exception) {
                            isSubmitting = false; submitError = e.message ?: "Submission failed"
                        }
                    }
                },
                onDismiss = { showSummary = false }
            )
        }
        if (showBackgroundAborted) com.example.thermohammer.ui.overlays.BackgroundAbortedOverlay { showBackgroundAborted = false }
        if (showManualCancelled) com.example.thermohammer.ui.overlays.ManualCancelledOverlay { showManualCancelled = false }
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
