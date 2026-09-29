package com.example.thermohammer.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.thermohammer.engine.Attribution
import com.example.thermohammer.engine.RunPhase
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressEngineFactory
import com.example.thermohammer.engine.TestDuration
import com.example.thermohammer.network.toWire
import com.example.thermohammer.ui.components.CoreStatusView
import com.example.thermohammer.ui.components.PhaseRail
import com.example.thermohammer.ui.components.StabilityChart
import com.example.thermohammer.ui.components.ThermalMonitoringPanel
import com.example.thermohammer.ui.components.ThermalReactor
import com.example.thermohammer.ui.theme.*
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

    // Thermal state polling supplements the listener (dedup inside engine)
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) {
            while (state.isRunning) {
                engine.setThermalState(engine.getThermalStateFromSystem())
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedDuration by remember { mutableStateOf(TestDuration.MINUTES_5) }
    var selectedThreadingType by remember { mutableStateOf(com.example.thermohammer.engine.StressThreadingType.MULTI) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Forge.bg)
            .forgeGrain()
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(targetState = selectedTab, animationSpec = tween(280), label = "tab_fade") { tab ->
                    when (tab) {
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
            }
            if (!state.isRunning) {
                BottomTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }
        }

        // Back on leaderboard tab → return to diagnostics (wrapper handles tab 0)
        BackHandler(enabled = selectedTab == 1) { selectedTab = 0 }
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
    var preflightReport by remember { mutableStateOf<com.example.thermohammer.engine.PreflightReport?>(null) }
    var preflightChecking by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val haptics = rememberThermoHaptics()

    // Overlay states
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
    var showComparison by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val store = remember { com.example.thermohammer.data.PendingResultStore(context) }
    var pendingCount by remember { mutableIntStateOf(0) }

    // ── Live pre-flight verification — while the checklist is open, gates
    // re-run continuously so a fix (unplug charger, wait for idle) flips the
    // LED green without closing the dialog. Keeps last good report on error.
    LaunchedEffect(showPreTest) {
        while (showPreTest) {
            // Skeleton/scanline only on the first check — re-verification runs
            // silently so fixed gates flip green in place without flicker.
            if (preflightReport == null) preflightChecking = true
            try {
                preflightReport = engine.runPreflight()
            } catch (_: Exception) { /* keep last good report */ }
            preflightChecking = false
            kotlinx.coroutines.delay(1500)
        }
    }

    // ── Back stack: overlays → run-guard → double-tap exit ─────────────────────
    val anyOverlay = showPreTest || showSummary || showConnectionRequest || showComparison ||
            showBackgroundAborted || showManualCancelled || showServerInit || showServerError

    BackHandler(enabled = anyOverlay) {
        when {
            showSummary -> { showSummary = false; engine.resetTestResult() }
            showConnectionRequest -> showConnectionRequest = false
            showComparison -> showComparison = false
            showBackgroundAborted -> { showBackgroundAborted = false; engine.resetTestResult() }
            showManualCancelled -> { showManualCancelled = false; engine.resetTestResult() }
            showServerError -> showServerError = false
            showPreTest -> showPreTest = false
            else -> Unit // server-init is transient — swallow
        }
    }
    // Back during a run — don't let it silently kill the session
    BackHandler(enabled = state.isRunning && !anyOverlay) {
        Toast.makeText(context, "Run in progress — use HOLD TO ABORT", Toast.LENGTH_SHORT).show()
    }
    // Back while idle — press twice to exit
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = !state.isRunning && !anyOverlay) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 1800L) (context as? android.app.Activity)?.finish()
        else {
            lastBackAt = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (state.wasCompleted || state.wasCancelledByBackground || state.elapsedSeconds > 0) {
                engine.resetTestResult()
            }
        }
    }

    LaunchedEffect(showSummary, state.isRunning) {
        pendingCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.getAllResults().size }
    }

    // ── Haptics: thud on confirmed onset, seal on verdict — no per-stamp tick ──
    var lastStampCount by remember { mutableIntStateOf(0) }
    var onsetFired by remember { mutableStateOf(false) }
    LaunchedEffect(state.recordedStamps.size) {
        if (state.recordedStamps.size > lastStampCount) {
            lastStampCount = state.recordedStamps.size
            // Onset detection — first stamp <90% of baseline (single-fire)
            val b = state.baselineIps
            val s = state.recordedStamps.lastOrNull()
            if (!onsetFired && b > 0 && s != null && s.ipsTotal < b * 0.9) {
                onsetFired = true
                haptics.onset()
            }
        }
    }
    LaunchedEffect(state.isRunning) {
        if (state.isRunning) { lastStampCount = 0; onsetFired = false }
    }
    LaunchedEffect(state.wasCompleted) {
        if (state.wasCompleted) {
            if (state.scorecard?.isVerified == true) haptics.confirmed() else haptics.rejected()
        }
    }

    LaunchedEffect(state.isRunning) {
        if (!state.isRunning && state.elapsedSeconds > 0) {
            isSubmitting = false; submitSuccess = null; submitError = null
            if (state.wasCompleted) {
                // Automatically save run to pending results in background thread!
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val stamps = state.recordedStamps
                        val card = state.scorecard
                        val finalStab = card?.deliveredCapacity
                            ?: state.chartPoints.lastOrNull()?.score
                            ?: state.overallStability

                        val minStab = card?.minSustained
                            ?: state.chartPoints.minOfOrNull { it.score }
                            ?: state.overallStability
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
                            encryptionKey = "",
                            initialBatteryLevel = state.initialBatteryLevel,
                            finalBatteryLevel = state.finalBatteryLevel,
                            initialBatteryTemp = state.initialBatteryTemp,
                            finalBatteryTemp = state.finalBatteryTemp,
                            schemaVersion = 2,
                            baselineScore = card?.baselineIps?.toLong() ?: 0L,
                            sustainedRatio = card?.sustainedRatio ?: 0f,
                            deliveredCapacity = card?.deliveredCapacity ?: 0f,
                            throttleOnsetSec = card?.throttleOnsetSec ?: -1,
                            timeInThrottlePct = card?.timeInThrottlePct ?: 0f,
                            perfPerWatt = card?.perfPerWatt ?: 0f,
                            thermalEfficiency = card?.thermalEfficiency ?: 0f,
                            confidence = card?.confidence ?: 0,
                            validityFlags = card?.validityFlags ?: 0,
                            socModel = engine.getSocModel(),
                            clusterTopology = engine.topology.describe(),
                            governor = engine.governor
                        )
                        com.example.thermohammer.data.PendingResultStore(context).saveResult(pending)
                        currentPendingResultId = pending.id
                    } catch (e: Exception) {
                        // Ignore background errors
                    }
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(4.dp))
            AppHeader(state, isNetworkConnected)

            // Per-phase progress: cooldown vs its cap, warm-up/calibration from
            // the pre-run sample counter (250ms each), measured vs duration.
            val durationSec = state.testDuration.seconds ?: 300
            val railProgress: Float
            val railLabel: String
            when {
                state.wasCompleted -> { railProgress = 1f; railLabel = "DONE" }
                state.phase == RunPhase.COOLDOWN -> {
                    railProgress = (state.cooldownElapsedSec /
                            (StressEngine.COOLDOWN_MAX_MS / 1000f)).coerceIn(0f, 1f)
                    railLabel = "%s° → %.0f°".format(
                        if (state.cpuTemp > 0f) "%.1f".format(state.cpuTemp) else "--",
                        state.cooldownTargetC
                    )
                }
                state.phase == RunPhase.WARMUP -> {
                    railProgress = (state.preRunIps.size * 0.25f /
                            (StressEngine.WARMUP_MS / 1000f)).coerceIn(0f, 1f)
                    railLabel = "settling"
                }
                state.phase == RunPhase.CALIBRATION -> {
                    railProgress = ((state.preRunIps.size - state.calibrationMarkIdx)
                            .coerceAtLeast(0) * 0.25f /
                            (StressEngine.CALIBRATION_MS / 1000f)).coerceIn(0f, 1f)
                    railLabel = "locking baseline"
                }
                state.phase == RunPhase.MEASURED -> {
                    val remaining = (durationSec - state.elapsedSeconds).coerceAtLeast(0)
                    railProgress = (state.elapsedSeconds.toFloat() / durationSec).coerceIn(0f, 1f)
                    railLabel = "T-%02d:%02d".format(remaining / 60, remaining % 60)
                }
                else -> { railProgress = 0f; railLabel = "" }
            }
            PhaseRail(
                phase = state.phase,
                preflightActive = showPreTest,
                completed = state.wasCompleted,
                progress = railProgress,
                progressLabel = railLabel
            )

            // ── The Thermal Reactor — hero instrument ─────────────────────────
            ThermalReactor(state = state, topology = engine.topology)

            // Attribution chip — the honest verdict-in-progress
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isRunning && state.phase == RunPhase.MEASURED,
                enter = androidx.compose.animation.fadeIn(tween(300)) +
                        androidx.compose.animation.expandVertically(tween(300)),
                exit = androidx.compose.animation.fadeOut(tween(200)) +
                       androidx.compose.animation.shrinkVertically(tween(200))
            ) {
                AttributionChip(state.currentAttribution)
            }
            // Cooldown chip — live descent toward the target temp
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isRunning && state.phase == RunPhase.COOLDOWN,
                enter = androidx.compose.animation.fadeIn(tween(300)) +
                        androidx.compose.animation.expandVertically(tween(300)),
                exit = androidx.compose.animation.fadeOut(tween(200)) +
                       androidx.compose.animation.shrinkVertically(tween(200))
            ) {
                CooldownChip(state)
            }

            if (pendingCount > 0 && !state.isRunning) {
                PendingRunsBanner(pendingCount, onNavigateToLeaderboard)
            }
            if (!state.isRunning) {
                DurationPicker(selected = selectedDuration, onSelect = onDurationChange)
                ThreadingPicker(selected = selectedThreadingType, onSelect = onThreadingChange)
            }
            ControlButton(
                state,
                onStart = {
                    showPreTest = true
                    preflightReport = null
                },
                onStop = { engine.stopTest() }
            )
            StabilityChart(
                points = state.chartPoints,
                events = state.thermalEvents,
                stamps = state.recordedStamps,
                baselineIps = state.baselineIps,
                preRunIps = state.preRunIps,
                preRunMarkIdx = state.calibrationMarkIdx
            )
            if (state.coreImpacts.isNotEmpty()) {
                CoreStatusView(
                    coreImpacts = state.coreImpacts,
                    cpuFrequencies = state.cpuFrequencies,
                    topology = engine.topology
                )
            }
            ThermalMonitoringPanel(state)
            Spacer(Modifier.height(16.dp))
        }

        // ── Overlays — animated entrances (fade + gentle scale-in) ────────────
        val overlayEnter = fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.94f)
        val overlayExit = fadeOut(tween(160))

        AnimatedVisibility(showPreTest, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.PreTestOverlay(
                report = preflightReport,
                checking = preflightChecking,
                onCancel = { showPreTest = false },
                onProceed = {
                    showPreTest = false
                    engine.clearSession()
                    engine.startTest(selectedDuration, selectedThreadingType)
                }
            )
        }

        AnimatedVisibility(showSummary, enter = overlayEnter, exit = overlayExit) {
            val stamps = state.recordedStamps
            val card = state.scorecard
            val finalStab = card?.deliveredCapacity
                ?: state.chartPoints.lastOrNull()?.score ?: state.overallStability

            val minStab = card?.minSustained ?: -1f
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
                deliveredCapacity = card?.deliveredCapacity,
                sustainedRatio = card?.sustainedRatio,
                throttleOnsetSec = card?.throttleOnsetSec,
                confidence = card?.confidence,
                validityFlags = card?.validityFlags ?: 0,
                isVerified = card?.isVerified ?: false,
                onSubmit = {
                    isSubmitting = true
                    coroutineScope.launch {
                        try {
                            val durationType = when (state.testDuration) {
                                TestDuration.MINUTES_5 -> 0; TestDuration.MINUTES_15 -> 1; TestDuration.MINUTES_30 -> 2
                            }
                            val session = com.example.thermohammer.network.ApiClient.api.createSession()
                            val wireStamps = stamps.toWire()
                            val hash = com.example.thermohammer.network.ThermoHasher.computeHash(session.encryptionKey, wireStamps)
                            val metaCanonical = "v2|$durationType|${state.testThreadingType.value}|${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }}|${engine.getDeviceModel()}|${engine.getAndroidVersion()}|${card?.baselineIps?.toLong() ?: 0L}|${"%.4f".format(java.util.Locale.US, card?.deliveredCapacity ?: 0f)}|${card?.validityFlags ?: 0}"
                            val hashV2 = com.example.thermohammer.network.ThermoHasher.computeHashV2(session.encryptionKey, metaCanonical, wireStamps)
                            val payload = com.example.thermohammer.network.HammerPayload(
                                stamps = wireStamps, type = durationType,
                                testThreadingType = state.testThreadingType.value,
                                deviceManufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() },
                                deviceModel = engine.getDeviceModel(), os = 2,
                                osVersion = engine.getAndroidVersion(),
                                sessionId = session.id, hash = hash,
                                schemaVersion = 2,
                                baselineScore = card?.baselineIps?.toLong(),
                                deliveredCapacity = card?.deliveredCapacity?.toDouble(),
                                sustainedRatio = card?.sustainedRatio?.toDouble(),
                                throttleOnsetSec = card?.throttleOnsetSec,
                                confidence = card?.confidence,
                                validityFlags = card?.validityFlags,
                                socModel = engine.getSocModel(),
                                clusterTopology = engine.topology.describe(),
                                governor = engine.governor,
                                hashV2 = hashV2
                            )
                            com.example.thermohammer.network.ApiClient.api.submitScore(payload)

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
        AnimatedVisibility(showConnectionRequest, enter = overlayEnter, exit = overlayExit) {
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
        AnimatedVisibility(showComparison, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.ComparisonOverlay(onDismiss = { showComparison = false })
        }
        AnimatedVisibility(showBackgroundAborted, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.BackgroundAbortedOverlay { showBackgroundAborted = false; engine.resetTestResult() }
        }
        AnimatedVisibility(showManualCancelled, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.ManualCancelledOverlay { showManualCancelled = false; engine.resetTestResult() }
        }
        AnimatedVisibility(showServerInit, enter = fadeIn(tween(200)), exit = overlayExit) {
            com.example.thermohammer.ui.overlays.ServerInitOverlay()
        }
        AnimatedVisibility(showServerError, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.ServerErrorOverlay(
            errorMessage = serverErrorMsg,
            onCancel = { showServerError = false },
            onRunOffline = { showServerError = false; engine.clearSession(); engine.startTest(selectedDuration, selectedThreadingType) }
        )
        }
    }
}

// ── Cooldown chip — live descent toward the fair-start target ─────────────────

@Composable
private fun CooldownChip(state: com.example.thermohammer.engine.StressState) {
    val color = Forge.phaseCooldown
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("❄", color = color, fontSize = 9.sp)
        Text(
            "COOLING %s° → %.0f°  ·  %ds".format(
                if (state.cpuTemp > 0f) "%.1f".format(state.cpuTemp) else "--",
                state.cooldownTargetC,
                state.cooldownElapsedSec
            ),
            style = TextStyle(
                color = color, fontSize = 9.sp, fontFamily = InstrumentMono,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp
            )
        )
    }
}

// ── Attribution chip — the live "why" under the reactor ───────────────────────

@Composable
private fun AttributionChip(attribution: Attribution) {
    val color = attributionColor(attribution)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(
            attributionLabel(attribution),
            style = TextStyle(
                color = color, fontSize = 9.sp, fontFamily = InstrumentMono,
                fontWeight = FontWeight.Black, letterSpacing = 1.sp
            )
        )
    }
}

// ── Tab bar ────────────────────────────────────────────────────────────────────

@Composable
fun BottomTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf("⬤  DIAGNOSTICS" to 0, "♛  LEADERBOARD" to 1)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Forge.surface.copy(alpha = 0.95f))
            .border(width = 1.dp, color = Forge.hairline)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { (label, idx) ->
            val isSelected = selectedTab == idx
            val color by animateColorAsState(
                if (isSelected) Forge.ink0 else Forge.ink2,
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
                    style = TextStyle(
                        color = color, fontSize = 10.sp, fontFamily = InstrumentMono,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                )
                // sliding underline indicator
                val underlineW by animateDpAsState(
                    if (isSelected) 18.dp else 0.dp,
                    animationSpec = tween(300, easing = FastOutSlowInEasing), label = "tab_ul_$idx"
                )
                if (underlineW > 0.dp) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(underlineW)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Forge.phaseMeasured)
                    )
                }
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
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised)
            .border(1.dp, Ramp.at(0.5f).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Ramp.at(0.5f).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("⚠", color = Ramp.at(0.5f), fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "UNSUBMITTED RESULTS",
                style = ThermoType.data(10f, Forge.ink0, FontWeight.Bold)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$count locally saved run${if (count > 1) "s" else ""} — tap to submit",
                style = ThermoType.annotation(8f, Forge.ink1)
            )
        }
        Text("→", color = Forge.ink2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
