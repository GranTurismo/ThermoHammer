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
import com.example.thermohammer.engine.RunKind
import com.example.thermohammer.engine.RunPhase
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressEngineFactory
import com.example.thermohammer.engine.StressMode
import com.example.thermohammer.engine.StressThreadingType
import com.example.thermohammer.engine.TestDuration
import com.example.thermohammer.data.BenchmarkHistoryStore
import com.example.thermohammer.data.BenchmarkRecord
import com.example.thermohammer.data.RunHistoryStore
import com.example.thermohammer.network.BenchmarkUpload
import com.example.thermohammer.data.RunRecord
import com.example.thermohammer.data.UpdateImpact
import com.example.thermohammer.ui.components.UpdateImpactCard
import com.example.thermohammer.network.toWire
import com.example.thermohammer.ui.components.CoreStatusView
import com.example.thermohammer.ui.components.PhaseRail
import com.example.thermohammer.ui.components.StabilityChart
import com.example.thermohammer.ui.components.ThermalMonitoringPanel
import com.example.thermohammer.ui.components.ThermalReactor
import com.example.thermohammer.ui.components.ThermalXRayCard
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

    // the welcome: on first launch, and whenever the header's "?" asks for it
    val welcomePrefs = remember { context.getSharedPreferences("welcome", Context.MODE_PRIVATE) }
    var showWelcome by remember { mutableStateOf(!welcomePrefs.getBoolean("seen", false)) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedDuration by remember { mutableStateOf(TestDuration.MINUTES_5) }
    var selectedThreadingType by remember { mutableStateOf(com.example.thermohammer.engine.StressThreadingType.MULTI) }
    var selectedMode by remember { mutableStateOf(com.example.thermohammer.engine.StressMode.CPU) }
    var selectedKind by remember { mutableStateOf(RunKind.BENCHMARK) }

    CompositionLocalProvider(LocalShowIntro provides { showWelcome = true }) {
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
                            selectedMode = selectedMode,
                            selectedKind = selectedKind,
                            onKindChange = { selectedKind = it },
                            onDurationChange = { selectedDuration = it },
                            onThreadingChange = { selectedThreadingType = it },
                            onModeChange = { selectedMode = it },
                            onNavigateToLeaderboard = { selectedTab = 1 }
                        )
                        1 -> LeaderboardScreen(isNetworkConnected.value)
                    }
                }
            }
            AnimatedVisibility(
                visible = !state.isRunning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                BottomTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            }
        }

        // Back on leaderboard tab → return to diagnostics (wrapper handles tab 0)
        BackHandler(enabled = selectedTab == 1) { selectedTab = 0 }

        AnimatedVisibility(showWelcome && !state.isRunning, enter = fadeIn(tween(400)),
            exit = fadeOut(tween(350)) + scaleOut(tween(350), targetScale = 1.04f)) {
            WelcomeScreen(onDone = {
                welcomePrefs.edit().putBoolean("seen", true).apply()
                showWelcome = false
            })
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
    selectedMode: com.example.thermohammer.engine.StressMode,
    selectedKind: RunKind,
    onKindChange: (RunKind) -> Unit,
    onDurationChange: (TestDuration) -> Unit,
    onThreadingChange: (com.example.thermohammer.engine.StressThreadingType) -> Unit,
    onModeChange: (com.example.thermohammer.engine.StressMode) -> Unit,
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

    // ── GPU benchmark: result card, local history, submission ──
    var showBenchResult by remember { mutableStateOf(false) }
    var benchErrorMsg by remember { mutableStateOf<String?>(null) }
    var benchSubmitting by remember { mutableStateOf(false) }
    var benchSubmitMsg by remember { mutableStateOf<String?>(null) }
    var benchSubmitErr by remember { mutableStateOf<String?>(null) }
    var benchRecordId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val store = remember { com.example.thermohammer.data.PendingResultStore(context) }
    var pendingCount by remember { mutableIntStateOf(0) }

    // ── Update impact: this phone compared with itself across an OS update ──
    val historyStore = remember { RunHistoryStore(context) }
    val benchStore = remember { BenchmarkHistoryStore(context) }
    // Test 3 needs Vulkan ray tracing — probed once off the main thread (creates a Vulkan instance)
    var rtProblem by remember { mutableStateOf<String?>(null) }
    var rtChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rtProblem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { engine.rayTracingSkipReason() }
        rtChecked = true
    }
    var benchRecords by remember { mutableStateOf<List<BenchmarkRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        benchRecords = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { benchStore.current() }
    }
    fun submitBenchmark(record: BenchmarkRecord) {
        benchSubmitting = true; benchSubmitErr = null
        coroutineScope.launch {
            try {
                val res = BenchmarkUpload.submit(context, record, engine.getSocModel())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { benchStore.markSubmitted(record.id) }
                benchRecords = benchStore.current()
                benchSubmitMsg = "SUBMITTED · RANK #${res.rank}"
                if (!showBenchResult) Toast.makeText(context, "Benchmark submitted — rank #${res.rank}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                benchSubmitErr = e.message ?: "Submission failed"
                if (!showBenchResult) Toast.makeText(context, "Submit failed: $benchSubmitErr", Toast.LENGTH_SHORT).show()
            }
            benchSubmitting = false
        }
    }
    var historyTick by remember { mutableIntStateOf(0) }
    var updateInsight by remember { mutableStateOf<UpdateImpact.Insight?>(null) }
    LaunchedEffect(historyTick) {
        updateInsight = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            UpdateImpact.insight(
                history = historyStore.all(),
                deviceModel = engine.getDeviceModel(),
                osVersion = engine.getAndroidVersion(),
                osBuild = engine.getOsBuild().ifBlank { null },
                securityPatch = engine.getSecurityPatch().ifBlank { null }
            )?.takeUnless { historyStore.isDismissed(it) }
        }
    }

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
            showBackgroundAborted || showManualCancelled || showServerInit || showServerError ||
            showBenchResult || benchErrorMsg != null

    BackHandler(enabled = anyOverlay) {
        when {
            showBenchResult -> { showBenchResult = false; engine.resetTestResult() }
            benchErrorMsg != null -> { benchErrorMsg = null; engine.resetTestResult() }
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
        if (!state.isRunning && state.elapsedSeconds > 0 && state.runKind == RunKind.BENCHMARK) {
            val result = state.benchResult
            when {
                state.wasCompleted && result != null -> {
                    val record = BenchmarkRecord.of(result, java.util.UUID.randomUUID().toString(), System.currentTimeMillis())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { benchStore.add(record) }
                    benchRecords = benchStore.current()
                    benchRecordId = record.id
                    benchSubmitting = false; benchSubmitMsg = null; benchSubmitErr = null
                    showBenchResult = true
                }
                state.benchError != null -> benchErrorMsg = state.benchError
                state.wasCancelledByBackground -> showBackgroundAborted = true
                else -> showManualCancelled = true
            }
            return@LaunchedEffect
        }
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
                            osBuild = engine.getOsBuild().ifBlank { null },
                            securityPatch = engine.getSecurityPatch().ifBlank { null },
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
                            governor = engine.governor,
                            stressMode = state.stressMode.value,
                            gpuBaselineFps = card?.gpuBaselineFps ?: 0.0,
                            gpuDeliveredCapacity = card?.gpuDeliveredCapacity ?: 0f,
                            gpuSustainedRatio = card?.gpuSustainedRatio ?: 0f,
                            gpuName = card?.gpuName ?: ""
                        )
                        com.example.thermohammer.data.PendingResultStore(context).saveResult(pending)
                        currentPendingResultId = pending.id

                        // Local history for the before/after-update comparison (never uploaded).
                        if (card != null) {
                            historyStore.add(RunRecord(
                                id = pending.id,
                                timestamp = pending.timestamp,
                                deviceModel = pending.deviceModel,
                                osVersion = pending.osVersion,
                                osBuild = pending.osBuild,
                                securityPatch = pending.securityPatch,
                                durationType = durationType,
                                threadingType = state.testThreadingType.value,
                                stressMode = state.stressMode.value,
                                capacity = card.deliveredCapacity.toDouble(),
                                onsetSec = card.throttleOnsetSec,
                                validityFlags = card.validityFlags,
                                confidence = card.confidence
                            ))
                            historyTick++
                        }
                    } catch (e: Exception) {
                        // Ignore background errors
                    }
                }

                // offline: ask for a connection first — unless the run is unverified and can't be submitted anyway
                if (isNetworkConnected || !com.example.thermohammer.engine.ValidityFlag.submittable(state.scorecard?.validityFlags ?: 0)) {
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
                state.phase == RunPhase.COOLDOWN && state.busyWait -> {
                    railProgress = (state.cooldownElapsedSec /
                            ((StressEngine.COOLDOWN_MAX_MS + StressEngine.QUIET_MAX_MS) / 1000f)).coerceIn(0f, 1f)
                    railLabel = "getting ready"
                }
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

            AnimatedVisibility(
                visible = updateInsight != null && !state.isRunning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                updateInsight?.let { insight ->
                    UpdateImpactCard(
                        insight = insight,
                        onRetest = { rec ->
                            // Repeat exactly the test the old result came from, then open pre-flight.
                            onKindChange(RunKind.STRESS)
                            onDurationChange(when (rec.durationType) {
                                1 -> TestDuration.MINUTES_15
                                2 -> TestDuration.MINUTES_30
                                else -> TestDuration.MINUTES_5
                            })
                            onThreadingChange(StressThreadingType.entries.firstOrNull { it.value == rec.threadingType }
                                ?: StressThreadingType.MULTI)
                            onModeChange(StressMode.entries.firstOrNull { it.value == rec.stressMode }
                                ?: StressMode.CPU)
                            showPreTest = true
                            preflightReport = null
                        },
                        onDismiss = { historyStore.dismiss(insight); historyTick++ }
                    )
                }
            }
            AnimatedVisibility(
                visible = pendingCount > 0 && !state.isRunning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                PendingRunsBanner(pendingCount, onNavigateToLeaderboard)
            }
            AnimatedVisibility(
                visible = !state.isRunning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RunKindPicker(selected = selectedKind, onSelect = onKindChange)
                    if (selectedKind == RunKind.BENCHMARK) {
                        val best = benchRecords.maxByOrNull { it.score }
                        val last = benchRecords.lastOrNull()
                        BenchmarkInfoCard(
                            rayTracingProblem = rtProblem,
                            rayTracingChecked = rtChecked,
                            best = best,
                            last = last,
                            canSubmitLast = last != null && !last.submitted && last.validityFlags == 0 && isNetworkConnected,
                            lastNeedsConnection = last != null && !last.submitted && last.validityFlags == 0 && !isNetworkConnected,
                            submitting = benchSubmitting && !showBenchResult,
                            onSubmitLast = { last?.let { submitBenchmark(it) } }
                        )
                    } else {
                        DurationPicker(selected = selectedDuration, onSelect = onDurationChange)
                        ModePicker(selected = selectedMode, onSelect = onModeChange)
                        if (selectedMode.usesCpu) {
                            ThreadingPicker(selected = selectedThreadingType, onSelect = onThreadingChange)
                        }
                    }
                }
            }
            ControlButton(
                state,
                onStart = {
                    showPreTest = true
                    preflightReport = null
                },
                onStop = { engine.stopTest() },
                startLabel = if (selectedKind == RunKind.BENCHMARK) "RUN BENCHMARK" else "INITIATE STRESS TEST"
            )
            if (selectedKind == RunKind.STRESS) StabilityChart(
                points = state.chartPoints,
                events = state.thermalEvents,
                stamps = state.recordedStamps,
                baselineIps = state.baselineIps,
                preRunIps = state.preRunIps,
                preRunMarkIdx = state.calibrationMarkIdx,
                gpuBaselineFps = if (state.stressMode == com.example.thermohammer.engine.StressMode.COMBINED) state.gpuBaselineFps else 0.0,
                preRunGpuFps = state.preRunGpuFps
            )
            AnimatedVisibility(
                visible = state.coreImpacts.isNotEmpty() && selectedKind == RunKind.STRESS,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                CoreStatusView(
                    coreImpacts = state.coreImpacts,
                    cpuFrequencies = state.cpuFrequencies,
                    topology = engine.topology
                )
            }
            ThermalXRayCard(state)
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
                    if (selectedKind == RunKind.BENCHMARK) engine.startBenchmark()
                    else engine.startTest(selectedDuration, selectedThreadingType, selectedMode)
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
                gpuBaselineFps = card?.gpuBaselineFps ?: 0.0,
                gpuDeliveredCapacity = card?.gpuDeliveredCapacity,
                gpuSustainedRatio = card?.gpuSustainedRatio,
                gpuName = card?.gpuName ?: "",
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
                                osBuild = engine.getOsBuild().ifBlank { null },
                                securityPatch = engine.getSecurityPatch().ifBlank { null },
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
                                hashV2 = hashV2,
                                stressMode = state.stressMode.value,
                                gpuBaselineFps = card?.gpuBaselineFps,
                                gpuDeliveredCapacity = card?.gpuDeliveredCapacity?.toDouble(),
                                gpuSustainedRatio = card?.gpuSustainedRatio?.toDouble(),
                                gpuName = card?.gpuName?.ifEmpty { null }
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
        AnimatedVisibility(showBenchResult, enter = overlayEnter, exit = overlayExit) {
            state.benchResult?.let { result ->
                com.example.thermohammer.ui.overlays.BenchmarkResultOverlay(
                    result = result,
                    deviceName = engine.getDeviceModel(),
                    history = benchRecords,
                    recordId = benchRecordId,
                    osVersion = engine.getAndroidVersion(),
                    isNetworkConnected = isNetworkConnected,
                    isSubmitting = benchSubmitting,
                    submitMessage = benchSubmitMsg,
                    submitError = benchSubmitErr,
                    onSubmit = { benchRecords.firstOrNull { it.id == benchRecordId }?.let { submitBenchmark(it) } },
                    onCopyReport = {
                        val version = appBuildLabel(context)
                        val text = com.example.thermohammer.engine.BenchmarkReport.text(
                            result, engine.getDeviceModel(), "${engine.getAndroidVersion()} (${engine.getOsBuild()})",
                            engine.getSocModel().ifBlank { "?" }, version,
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ThermoHammer benchmark report", text))
                        Toast.makeText(context, "Report copied — paste it anywhere", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showBenchResult = false; engine.resetTestResult() }
                )
            }
        }
        AnimatedVisibility(benchErrorMsg != null, enter = overlayEnter, exit = overlayExit) {
            com.example.thermohammer.ui.overlays.BenchmarkErrorOverlay(benchErrorMsg ?: "") {
                benchErrorMsg = null; engine.resetTestResult()
            }
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
            onRunOffline = { showServerError = false; engine.clearSession(); engine.startTest(selectedDuration, selectedThreadingType, selectedMode) }
        )
        }

        // ── GPU stage — full-screen live render while a GPU mode is active ──
        AnimatedVisibility(
            state.isRunning && state.gpuActive,
            enter = fadeIn(tween(350)), exit = fadeOut(tween(250))
        ) {
            com.example.thermohammer.ui.components.GpuStage(
                state = state,
                onSurfaceReady = { engine.gpuAttachSurface(it) },
                onSurfaceGone = { engine.gpuDetachSurface() },
                onStop = { engine.stopTest() },
                onWebProgress = engine::webProgress,
                onWebDone = engine::webDone,
                onWebFailed = engine::webFailed
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
            // Busy: Android is still at work in the background (e.g. optimizing a just-installed
            // app) — part of the cooldown; the benchmark starts by itself once the phone is quiet.
            if (state.busyWait) "GETTING READY  ·  %ds".format(state.cooldownElapsedSec)
            else "COOLING %s° → %.0f°  ·  %ds".format(
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

/** "2.0 (13) · Play", "2.0 (13) · bench" or "… · sideloaded" — which build a report came from. */
fun appBuildLabel(context: Context): String = try {
    val pm = context.packageManager
    val info = pm.getPackageInfo(context.packageName, 0)
    val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
    val installer = if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    val source = when {
        context.packageName.endsWith(".bench") -> "bench"
        installer == "com.android.vending" -> "Play"
        else -> "sideloaded"
    }
    "${info.versionName ?: "?"} ($code) · $source"
} catch (_: Exception) { "?" }
