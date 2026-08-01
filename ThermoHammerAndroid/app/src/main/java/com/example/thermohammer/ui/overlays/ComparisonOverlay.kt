package com.example.thermohammer.ui.overlays

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.ComparisonEngine
import com.example.thermohammer.data.PendingResultStore
import com.example.thermohammer.data.PendingTestResult
import com.example.thermohammer.engine.StabilityPoint
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerDto
import com.example.thermohammer.ui.components.DualStabilityChart
import java.text.SimpleDateFormat
import java.util.*

fun HammerDto.toPendingTestResult(): PendingTestResult {
    val durationSec = when (type) {
        0 -> 300
        1 -> 900
        else -> 1800
    }
    val stab = stabilityPercentage.toFloat()
    return PendingTestResult(
        id = "online_$id",
        timestamp = System.currentTimeMillis(),
        durationSeconds = durationSec,
        testDurationType = type,
        testThreadingType = testThreadingType ?: 1,
        minStability = stab,
        finalStability = stab,
        worstThermalState = 0,
        stamps = stamps ?: emptyList(),
        deviceModel = deviceModel,
        deviceManufacturer = deviceManufacturer,
        osVersion = osVersion,
        sessionId = id,
        encryptionKey = ""
    )
}

@Composable
fun ComparisonOverlay(
    preselectedRunA: PendingTestResult? = null,
    preselectedRunB: PendingTestResult? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { PendingResultStore(context) }
    val localResults = remember { store.getAllResults() }

    var onlineResults by remember { mutableStateOf<List<PendingTestResult>>(emptyList()) }
    var isLoadingOnline by remember { mutableStateOf(false) }
    val fetchedStampsMap = remember { mutableStateMapOf<Int, List<DeviceHammerStamp>>() }

    LaunchedEffect(Unit) {
        try {
            isLoadingOnline = true
            val leaderboard = ApiClient.api.fetchLeaderboard()
            onlineResults = leaderboard.map { it.toPendingTestResult() }
        } catch (_: Exception) {
            // Ignore offline error
        } finally {
            isLoadingOnline = false
        }
    }

    val targetThreadingType = preselectedRunA?.testThreadingType ?: preselectedRunB?.testThreadingType ?: 1

    val allComparableRuns = remember(localResults, onlineResults, preselectedRunA, preselectedRunB, targetThreadingType) {
        val combined = (localResults + onlineResults).filter { it.testThreadingType == targetThreadingType }.toMutableList()
        preselectedRunA?.let { preA ->
            if (combined.none { (preA.sessionId > 0 && it.sessionId == preA.sessionId) || it.id == preA.id }) combined.add(0, preA)
        }
        preselectedRunB?.let { preB ->
            if (combined.none { (preB.sessionId > 0 && it.sessionId == preB.sessionId) || it.id == preB.id }) combined.add(0, preB)
        }
        combined
    }

    var selectedIndexA by remember {
        mutableIntStateOf(
            if (preselectedRunA != null) {
                allComparableRuns.indexOfFirst { (preselectedRunA.sessionId > 0 && it.sessionId == preselectedRunA.sessionId) || it.id == preselectedRunA.id }.coerceAtLeast(0)
            } else 0
        )
    }

    var selectedIndexB by remember {
        mutableIntStateOf(
            if (preselectedRunB != null) {
                allComparableRuns.indexOfFirst { (preselectedRunB.sessionId > 0 && it.sessionId == preselectedRunB.sessionId) || it.id == preselectedRunB.id }.coerceAtLeast(0)
            } else if (allComparableRuns.size > 1) 1 else 0
        )
    }

    LaunchedEffect(onlineResults) {
        if (onlineResults.isNotEmpty()) {
            preselectedRunA?.let { preA ->
                val idx = allComparableRuns.indexOfFirst { (preA.sessionId > 0 && it.sessionId == preA.sessionId) || it.id == preA.id }
                if (idx >= 0) selectedIndexA = idx
            }
            preselectedRunB?.let { preB ->
                val idx = allComparableRuns.indexOfFirst { (preB.sessionId > 0 && it.sessionId == preB.sessionId) || it.id == preB.id }
                if (idx >= 0) selectedIndexB = idx
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF101216))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            if (allComparableRuns.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚖️", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "UNIVERSAL RUN COMPARISON",
                        style = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No matching test runs available to compare. Single Thread tests can only be compared with Single Thread tests, and Multi Thread with Multi Thread.",
                        style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        Text("CLOSE", style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    }
                }
            } else {
                val rawRunA = allComparableRuns.getOrNull(selectedIndexA) ?: allComparableRuns.first()
                val rawRunB = allComparableRuns.getOrNull(selectedIndexB) ?: allComparableRuns.first()

                // Fetch online stamps from API if missing for Run A
                LaunchedEffect(rawRunA.sessionId) {
                    if (rawRunA.stamps.isEmpty() && rawRunA.sessionId > 0 && !fetchedStampsMap.containsKey(rawRunA.sessionId)) {
                        try {
                            val stamps = ApiClient.api.fetchStamps(rawRunA.sessionId)
                            fetchedStampsMap[rawRunA.sessionId] = stamps
                        } catch (_: Exception) {}
                    }
                }

                // Fetch online stamps from API if missing for Run B
                LaunchedEffect(rawRunB.sessionId) {
                    if (rawRunB.stamps.isEmpty() && rawRunB.sessionId > 0 && !fetchedStampsMap.containsKey(rawRunB.sessionId)) {
                        try {
                            val stamps = ApiClient.api.fetchStamps(rawRunB.sessionId)
                            fetchedStampsMap[rawRunB.sessionId] = stamps
                        } catch (_: Exception) {}
                    }
                }

                val stampsA = fetchedStampsMap[rawRunA.sessionId] ?: rawRunA.stamps
                val stampsB = fetchedStampsMap[rawRunB.sessionId] ?: rawRunB.stamps

                val runA = remember(rawRunA, stampsA) { rawRunA.copy(stamps = stampsA) }
                val runB = remember(rawRunB, stampsB) { rawRunB.copy(stamps = stampsB) }

                val analysis = remember(runA, runB) { ComparisonEngine.analyze(runA, runB) }

                val pointsA = remember(runA, stampsA) {
                    if (stampsA.isNotEmpty()) {
                        val maxVal = stampsA.maxOfOrNull { it.score.toDouble() } ?: 1.0
                        stampsA.map { StabilityPoint((it.elapsedMs / 1000).toFloat(), ((it.score.toDouble() / maxVal) * 100.0).toFloat()) }
                    } else {
                        listOf(StabilityPoint(0f, runA.finalStability), StabilityPoint(runA.durationSeconds.toFloat(), runA.finalStability))
                    }
                }
                val pointsB = remember(runB, stampsB) {
                    if (stampsB.isNotEmpty()) {
                        val maxVal = stampsB.maxOfOrNull { it.score.toDouble() } ?: 1.0
                        stampsB.map { StabilityPoint((it.elapsedMs / 1000).toFloat(), ((it.score.toDouble() / maxVal) * 100.0).toFloat()) }
                    } else {
                        listOf(StabilityPoint(0f, runB.finalStability), StabilityPoint(runB.durationSeconds.toFloat(), runB.finalStability))
                    }
                }

                val modeText = if (targetThreadingType == 0) "1 THREAD" else "MULTI THREAD"

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Top Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "⚖️ COMPARISON ENGINE",
                                    style = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(modeText, style = TextStyle(color = Color(0xFF00E5FF), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                                }
                            }
                            Text(
                                "Side-by-side benchmark comparison",
                                style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    // Run Selectors with Dropdown Spinners (Run A vs Run B)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RunSpinnerDropdown(
                            label = "RUN A",
                            accentColor = Color(0xFF00E5FF),
                            selectedIndex = selectedIndexA,
                            runs = allComparableRuns,
                            onSelectIndex = { selectedIndexA = it },
                            isLoadingStamps = stampsA.isEmpty() && rawRunA.sessionId > 0,
                            modifier = Modifier.weight(1f)
                        )

                        RunSpinnerDropdown(
                            label = "RUN B",
                            accentColor = Color(0xFFFFAB00),
                            selectedIndex = selectedIndexB,
                            runs = allComparableRuns,
                            onSelectIndex = { selectedIndexB = it },
                            isLoadingStamps = stampsB.isEmpty() && rawRunB.sessionId > 0,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Dual Stability Chart Overlay
                    DualStabilityChart(
                        pointsA = pointsA,
                        labelA = runA.deviceModel,
                        pointsB = pointsB,
                        labelB = runB.deviceModel
                    )

                    // Key Metric Analytical Comparison Grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF14161B))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚡ DIAGNOSTIC METRICS COMPARISON", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCompareCard(
                                title = "FINAL STABILITY",
                                valA = "%.1f%%".format(Locale.US, runA.finalStability),
                                valB = "%.1f%%".format(Locale.US, runB.finalStability),
                                deltaText = null,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCompareCard(
                                title = "PEAK SCORE (IPS)",
                                valA = "%,d".format(Locale.US, analysis.peakScoreA.toLong()),
                                valB = "%,d".format(Locale.US, analysis.peakScoreB.toLong()),
                                deltaText = "%+.1f%%".format(Locale.US, analysis.peakScoreDeltaPct),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCompareCard(
                                title = "THROTTLE ONSET",
                                valA = analysis.throttleOnsetTimeA?.let { "${it}s" } ?: "No Throttle",
                                valB = analysis.throttleOnsetTimeB?.let { "${it}s" } ?: "No Throttle",
                                deltaText = null,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCompareCard(
                                title = "AVG SCORE (IPS)",
                                valA = "%,d".format(Locale.US, analysis.avgScoreA.toLong()),
                                valB = "%,d".format(Locale.US, analysis.avgScoreB.toLong()),
                                deltaText = "%+.1f%%".format(Locale.US, analysis.avgScoreDeltaPct),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Automated Analytical Summary Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF14161B), Color(0xFF19202E))
                                )
                            )
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("🤖", fontSize = 16.sp)
                            Text(
                                "ANALYTICAL ENGINE INSIGHTS",
                                style = TextStyle(color = Color(0xFF00E5FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            analysis.summaryText,
                            style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 16.sp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RunSpinnerDropdown(
    label: String,
    accentColor: Color,
    selectedIndex: Int,
    runs: List<PendingTestResult>,
    onSelectIndex: (Int) -> Unit,
    isLoadingStamps: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentRun = runs.getOrNull(selectedIndex) ?: runs.firstOrNull()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accentColor.copy(alpha = 0.06f))
            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, style = TextStyle(color = accentColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
            if (isLoadingStamps) {
                CircularProgressIndicator(Modifier.size(10.dp), color = accentColor, strokeWidth = 1.5.dp)
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentRun?.let { "${it.deviceManufacturer} ${it.deviceModel}" } ?: "Select Device",
                        style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = if (currentRun?.id?.startsWith("online_") == true) "Global Leaderboard" else "Local Run",
                        style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("▼", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(Color(0xFF1A1D24))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            ) {
                runs.forEachIndexed { idx, item ->
                    val isOnline = item.id.startsWith("online_")
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    if (isOnline) "🌐 ${item.deviceManufacturer} ${item.deviceModel}" else "📱 ${item.deviceManufacturer} ${item.deviceModel}",
                                    style = TextStyle(
                                        color = if (idx == selectedIndex) accentColor else Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (idx == selectedIndex) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                                Text(
                                    "Stability: %.1f%%".format(Locale.US, item.finalStability),
                                    style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                )
                            }
                        },
                        onClick = {
                            onSelectIndex(idx)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCompareCard(
    title: String,
    valA: String,
    valB: String,
    deltaText: String?,
    modifier: Modifier = Modifier
) {
    val cyan  = Color(0xFF00E5FF)
    val amber = Color(0xFFFFAB00)
    val green = Color(0xFF4CAF50)
    val red   = Color(0xFFEF5350)

    val deltaColor = deltaText?.let { d ->
        if (d.startsWith("+")) green else red
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Title
        Text(
            title,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )

        // RUN A
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "RUN A",
                style = TextStyle(color = cyan, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            )
            Text(
                valA,
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.06f))
        )

        // RUN B
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "RUN B",
                    style = TextStyle(color = amber, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                )
                if (deltaText != null && deltaColor != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(deltaColor.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            deltaText,
                            style = TextStyle(
                                color = deltaColor,
                                fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
            Text(
                valB,
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
    }
}
