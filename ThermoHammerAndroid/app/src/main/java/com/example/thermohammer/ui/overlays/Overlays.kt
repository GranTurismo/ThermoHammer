package com.example.thermohammer.ui.overlays

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.ThermalState
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.delay

// ── Shared chrome ──────────────────────────────────────────────────────────────

@Composable
fun OverlayContainer(onDismiss: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Forge.bg.copy(alpha = 0.88f))
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.clickable(enabled = false) {}) { content() }
    }
}

/** Glass instrument card — raised surface, hairline, corner ticks. */
@Composable
fun OverlayCard(width: Int = 320, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(width.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Forge.raised)
            .cornerTicks(Forge.ink2.copy(alpha = 0.55f))
            .border(1.dp, Forge.hairline, RoundedCornerShape(22.dp))
            .padding(24.dp),
        content = content
    )
}

@Composable
fun MonoLabel(text: String, size: Float = 10f, alpha: Float = 0.5f) = Text(
    text = text,
    style = TextStyle(
        color = Forge.ink0.copy(alpha = alpha),
        fontSize = size.sp,
        fontFamily = InstrumentMono,
        fontWeight = FontWeight.Bold
    )
)

@Composable
fun MonoTitle(text: String, size: Float = 14f, color: Color = Forge.ink0) = Text(
    text = text,
    style = TextStyle(
        color = color,
        fontSize = size.sp,
        fontFamily = InstrumentMono,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp
    )
)

@Composable
fun OverlayDivider() = HorizontalDivider(color = Forge.hairline, thickness = 1.dp)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, accent: Color = Forge.ink0) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Forge.bg,
                fontSize = 12.sp,
                fontFamily = InstrumentMono,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forge.ink0.copy(alpha = 0.08f))
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Forge.ink0,
                fontSize = 12.sp,
                fontFamily = InstrumentMono,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
fun SummaryRow(label: String, value: String, valueColor: Color = Forge.ink0) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = ThermoType.label(10f))
        Text(value, style = ThermoType.data(13f, valueColor, FontWeight.Black))
    }
}

// ── Instrument glyph helpers ──────────────────────────────────────────────────

@Composable
private fun RingBadge(color: Color, glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) { glyph() }
}

// ── Pre-Flight Gate Checklist ──────────────────────────────────────────────────
// A launch sequence, not a dialog: rows resolve under a sweep line, blocking
// failures lock the PROCEED button until canRun.

@Composable
fun PreTestOverlay(
    isCharging: Boolean? = null,
    isBatterySaver: Boolean? = null,
    isCellularActive: Boolean? = null,
    report: com.example.thermohammer.engine.PreflightReport? = null,
    checking: Boolean = false,
    onCancel: () -> Unit,
    onProceed: () -> Unit
) {
    val reduced = rememberReducedMotion()
    OverlayContainer {
        OverlayCard(width = 340) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RingBadge(Forge.phasePreFlight) {
                    Text("⌖", color = Forge.phasePreFlight, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(10.dp))
                MonoTitle("LAUNCH CHECKS", size = 13f)
                Spacer(Modifier.height(3.dp))
                Text(
                    "instrument verification sequence",
                    style = ThermoType.annotation(8f)
                )
            }
            Spacer(Modifier.height(16.dp))

            Box {
                Column(
                    Modifier
                        .heightIn(max = 330.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (checking) {
                        // Rows "arming" — placeholders reveal under the scanline
                        repeat(5) { GateRowSkeleton(it) }
                    } else {
                        GateRow("◇", "REMOVE PHONE CASE", "Trapped heat severely skews throttling scores.", Forge.phasePreFlight, GateState.ADVISORY)
                        if (report != null) {
                            report.gates.sortedByDescending { it.blocking }.forEachIndexed { i, gate ->
                                val gs = when {
                                    !gate.passed && gate.blocking -> GateState.FAIL
                                    !gate.passed -> GateState.WARN
                                    else -> GateState.PASS
                                }
                                GateRow(
                                    led = when (gs) { GateState.PASS -> "✓"; GateState.WARN -> "⚠"; else -> "⛔" },
                                    title = gate.title, detail = gate.detail,
                                    color = when (gs) { GateState.PASS -> Ramp.at(0.05f); GateState.WARN -> Ramp.at(0.5f); else -> Ramp.at(0.85f) },
                                    state = gs
                                )
                            }
                        } else {
                            // Legacy advisory mode
                            if (isBatterySaver == true) GateRow("⚠", "BATTERY SAVER ON", "Turn off Battery Saver for accurate clocks.", Ramp.at(0.5f), GateState.WARN)
                            else GateRow("✓", "BATTERY SAVER OFF", "Clocks are not externally capped.", Ramp.at(0.05f), GateState.PASS)
                            if (isCharging == true) GateRow("⛔", "CHARGER CONNECTED", "Unplug — charging heat forces throttling.", Ramp.at(0.85f), GateState.FAIL)
                            else GateRow("✓", "ON BATTERY", "No charger heat injection.", Ramp.at(0.05f), GateState.PASS)
                            if (isCellularActive == true) GateRow("⚠", "CELLULAR ACTIVE", "Airplane mode recommended.", Ramp.at(0.5f), GateState.WARN)
                            else GateRow("✓", "RADIO OFFLINE", "No cellular background heat.", Ramp.at(0.05f), GateState.PASS)
                        }
                        GateRow("⌖", "STAY IN FOREGROUND", "Minimizing aborts the run automatically.", Forge.phasePreFlight, GateState.ADVISORY)
                    }
                }
                // Scanline sweep while checking
                if (checking && !reduced) {
                    val sweep by rememberInfiniteTransition(label = "gate_scan").animateFloat(
                        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "gate_scan_f"
                    )
                    BoxWithConstraints(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.5.dp)
                                .offset(y = maxHeight * sweep)
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(Color.Transparent, Forge.phasePreFlight.copy(alpha = 0.7f), Color.Transparent)
                                    )
                                )
                        )
                    }
                }
            }

            if (checking) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "measuring idle load, start temperature & headroom…",
                    style = ThermoType.annotation(8f, Forge.phasePreFlight.copy(alpha = 0.8f)),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            } else if (report != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "◇ live — gates re-verify continuously while open",
                    style = ThermoType.annotation(8f, Forge.phaseMeasured.copy(alpha = 0.7f)),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }

            if (report != null && !report.canRun && !checking) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "⛔ blocking checks failed — resolve to start a verified run",
                    style = TextStyle(color = Ramp.at(0.85f), fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("CANCEL", onCancel) }
                Box(Modifier.weight(1f)) {
                    val enabled = !checking && (report == null || report.canRun)
                    Button(
                        onClick = onProceed,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Forge.phaseMeasured,
                            disabledContainerColor = Forge.ink0.copy(alpha = 0.12f)
                        )
                    ) {
                        Text(
                            if (checking) "ARMING…" else "PROCEED",
                            style = TextStyle(
                                color = if (enabled) Forge.bg else Forge.ink0.copy(alpha = 0.4f),
                                fontSize = 12.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private enum class GateState { PASS, WARN, FAIL, ADVISORY, ARMING }

@Composable
private fun GateRow(led: String, title: String, detail: String, color: Color, state: GateState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Forge.surface)
            .border(1.dp, color.copy(alpha = if (state == GateState.ADVISORY) 0.12f else 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status LED
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(led, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ThermoType.data(9.5f, Forge.ink0, FontWeight.Black))
            Spacer(Modifier.height(2.dp))
            Text(detail, style = ThermoType.annotation(8f, Forge.ink1), maxLines = 2)
        }
    }
}

@Composable
private fun GateRowSkeleton(i: Int) {
    val shimmer by rememberInfiniteTransition(label = "sk$i").animateFloat(
        0.3f, 0.7f, infiniteRepeatable(tween(900, delayMillis = i * 120), RepeatMode.Reverse), label = "skf$i"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Forge.surface)
            .border(1.dp, Forge.hairline, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Forge.ink1.copy(alpha = shimmer * 0.3f)))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.55f).height(9.dp).clip(RoundedCornerShape(3.dp)).background(Forge.ink1.copy(alpha = shimmer * 0.4f)))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(7.dp).clip(RoundedCornerShape(3.dp)).background(Forge.ink1.copy(alpha = shimmer * 0.25f)))
        }
    }
}

// ── The Verdict Card ───────────────────────────────────────────────────────────

@Composable
fun SummaryOverlay(
    duration: Int,
    minStability: Float,
    finalStability: Float,
    worstThermal: ThermalState,
    initialBatteryLevel: Int = 0,
    finalBatteryLevel: Int = 0,
    initialBatteryTemp: Float = 0f,
    finalBatteryTemp: Float = 0f,
    hasSession: Boolean,
    isNetworkConnected: Boolean,
    isSubmitting: Boolean,
    submitSuccess: String?,
    submitError: String?,
    deliveredCapacity: Float? = null,
    sustainedRatio: Float? = null,
    throttleOnsetSec: Int? = null,
    confidence: Int? = null,
    validityFlags: Int = 0,
    isVerified: Boolean = false,
    onSubmit: () -> Unit,
    onSavePending: () -> Unit,
    onDismiss: () -> Unit
) {
    val reduced = rememberReducedMotion()
    // Reveal cascade: numeral → seal → rows
    var stage by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        if (reduced) { stage = 3; return@LaunchedEffect }
        delay(80); stage = 1; delay(350); stage = 2; delay(250); stage = 3
    }
    val sealScale by animateFloatAsState(
        targetValue = if (stage >= 2) 1f else 1.4f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "seal_scale"
    )
    val sealAlpha by animateFloatAsState(
        targetValue = if (stage >= 2) 1f else 0f,
        animationSpec = tween(200), label = "seal_alpha"
    )

    val heroValue = deliveredCapacity ?: finalStability
    val heroColor = Ramp.forCapacity(heroValue)
    val flagNames = remember(validityFlags) { validityFlagNames(validityFlags) }

    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard(width = 340) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                MonoTitle("THE READING", size = 12f, color = Forge.ink1)
                Spacer(Modifier.height(4.dp))
                Text("diagnostic verdict — schema v2", style = ThermoType.annotation(7.5f))
            }
            Spacer(Modifier.height(18.dp))

            // ── Hero numeral ────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                TickerText(
                    value = if (stage >= 1) heroValue else 0f,
                    format = { "%.1f%%".format(it) },
                    color = heroColor, size = 52f,
                    fontFamily = DisplayFont, weight = FontWeight.Medium
                )
                Text(
                    if (deliveredCapacity != null) "DELIVERED CAPACITY" else "FINAL STABILITY",
                    style = ThermoType.label(8f)
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── The Seal ────────────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (isVerified) {
                    // VERIFIED — ring stamp, teal, stamped-in rotation
                    Box(
                        modifier = Modifier
                            .scale(sealScale)
                            .rotate(-8f)
                            .clip(RoundedCornerShape(50))
                            .border(2.5.dp, Forge.phaseMeasured.copy(alpha = sealAlpha), RoundedCornerShape(50))
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "⌖ VERIFIED",
                            style = TextStyle(
                                color = Forge.phaseMeasured.copy(alpha = sealAlpha),
                                fontSize = 13.sp, fontFamily = InstrumentMono,
                                fontWeight = FontWeight.Black, letterSpacing = 3.sp
                            )
                        )
                    }
                } else {
                    // FLAGGED — rectangular warning stamp
                    Box(
                        modifier = Modifier
                            .scale(sealScale)
                            .rotate(-3f)
                            .border(2.dp, Ramp.at(0.5f).copy(alpha = sealAlpha), RoundedCornerShape(4.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "⚠ FLAGGED",
                                style = TextStyle(
                                    color = Ramp.at(0.5f).copy(alpha = sealAlpha),
                                    fontSize = 12.sp, fontFamily = InstrumentMono,
                                    fontWeight = FontWeight.Black, letterSpacing = 2.sp
                                )
                            )
                            if (flagNames.isNotEmpty()) {
                                Text(
                                    flagNames.joinToString(" · "),
                                    style = ThermoType.annotation(7f, Ramp.at(0.5f).copy(alpha = sealAlpha * 0.8f))
                                )
                            }
                        }
                    }
                }
            }

            if (stage >= 3) {
                Spacer(Modifier.height(16.dp))
                OverlayDivider()
                Spacer(Modifier.height(12.dp))

                val mins = duration / 60; val secs = duration % 60
                SummaryRow("TEST TIME", "%02d:%02d".format(mins, secs))
                sustainedRatio?.let {
                    SummaryRow("SUSTAINED RATIO", "%.1f%%".format(it), Ramp.forCapacity(it))
                }
                if (minStability >= 0f) {
                    SummaryRow("MIN SUSTAINED", "%.0f%%".format(minStability), Ramp.forCapacity(minStability))
                } else {
                    SummaryRow("MIN SUSTAINED", "NO THROTTLE", Forge.phaseMeasured)
                }
                throttleOnsetSec?.let {
                    SummaryRow("THROTTLE ONSET", "${it}s", if (it < 60) Ramp.at(0.85f) else Ramp.at(0.5f))
                }
                SummaryRow("WORST THERMAL", thermalStateName(worstThermal), thermalStateColor(worstThermal))
                confidence?.let { conf ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CONFIDENCE", style = ThermoType.label(10f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // 5-segment gauge ▮▮▮▮▯
                            repeat(5) { i ->
                                Box(
                                    Modifier
                                        .size(width = 10.dp, height = 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (i < conf / 20) Ramp.at(1f - conf / 130f) else Forge.ink2.copy(alpha = 0.3f)
                                        )
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("$conf", style = ThermoType.data(11f, Forge.ink0, FontWeight.Black))
                        }
                    }
                }

                val tempDiff = finalBatteryTemp - initialBatteryTemp
                val tempDiffStr = if (initialBatteryTemp > 0f && finalBatteryTemp > 0f) {
                    "%+.1f°C (%.1f → %.1f)".format(tempDiff, initialBatteryTemp, finalBatteryTemp)
                } else "--"
                SummaryRow("BATTERY TEMP", tempDiffStr, Ramp.forTemp(finalBatteryTemp.coerceAtLeast(initialBatteryTemp)))

                val levelDrop = initialBatteryLevel - finalBatteryLevel
                val dropStr = if (initialBatteryLevel > 0 && finalBatteryLevel > 0) {
                    when {
                        levelDrop > 0 -> "-%d%% (%d%% → %d%%)".format(levelDrop, initialBatteryLevel, finalBatteryLevel)
                        levelDrop < 0 -> "+%d%% (%d%% → %d%%)".format(-levelDrop, initialBatteryLevel, finalBatteryLevel)
                        else -> "0%% (%d%% → %d%%)".format(initialBatteryLevel, finalBatteryLevel)
                    }
                } else "--"
                SummaryRow("BATTERY DRAIN", dropStr, Forge.ink0)
            }

            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(12.dp))

            // ── Leaderboard & pending ────────────────────────────────────────
            if (hasSession) {
                if (isNetworkConnected) {
                    when {
                        submitSuccess != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Text("✓ ", color = Forge.phaseMeasured, fontWeight = FontWeight.Black)
                            MonoLabel(submitSuccess, size = 11f, alpha = 1f)
                        }
                        submitError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("✗ SUBMISSION FAILED", color = Ramp.at(0.85f), fontSize = 11.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(4.dp))
                            Text(submitError, style = TextStyle(color = Forge.ink1.copy(alpha = 0.6f), fontSize = 9.sp, textAlign = TextAlign.Center))
                        }
                        isSubmitting -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Forge.phasePreFlight, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            MonoLabel("SUBMITTING SCORE...", size = 11f, alpha = 1f)
                        }
                        else -> Button(
                            onClick = onSubmit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Forge.phasePreFlight)
                        ) {
                            Text("⌖ SUBMIT TO LEADERBOARD", style = TextStyle(color = Forge.ink0, fontSize = 11.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("⌖ CONNECTION LOST", color = Ramp.at(0.5f), fontSize = 11.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Enable Wi-Fi or cellular to submit. Or save this result to pending history.",
                            style = TextStyle(color = Forge.ink1, fontSize = 10.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = onSavePending,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Forge.ink0.copy(alpha = 0.1f))
                                ) {
                                    Text("SAVE PENDING", style = TextStyle(color = Forge.ink0, fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = onSubmit,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Forge.phasePreFlight)
                                ) {
                                    Text("RETRY SUBMIT", style = TextStyle(color = Forge.ink0, fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("⌖ OFFLINE RUN", color = Forge.ink1.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Run offline — cannot be submitted to the leaderboard.",
                        style = TextStyle(color = Forge.ink2, fontSize = 9.sp, textAlign = TextAlign.Center)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS REPORT", onDismiss)
        }
    }
}

// ── Background Cancelled ──────────────────────────────────────────────────────

@Composable
fun BackgroundAbortedOverlay(onDismiss: () -> Unit) {
    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RingBadge(Ramp.at(0.85f)) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val s = 3.dp.toPx()
                        drawLine(Ramp.at(0.85f), Offset(0f, 0f), Offset(size.width, size.height), s, cap = StrokeCap.Round)
                        drawLine(Ramp.at(0.85f), Offset(0f, size.height), Offset(size.width, 0f), s, cap = StrokeCap.Round)
                    }
                }
                Spacer(Modifier.height(12.dp))
                MonoTitle("TEST ABORTED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "The run was aborted because the app left the foreground. Foreground presence is required for valid measurement.",
                style = TextStyle(color = Forge.ink1, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS", onDismiss)
        }
    }
}

// ── Manual Cancelled ──────────────────────────────────────────────────────────

@Composable
fun ManualCancelledOverlay(onDismiss: () -> Unit) {
    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RingBadge(Ramp.at(0.5f)) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Ramp.at(0.5f)))
                }
                Spacer(Modifier.height(12.dp))
                MonoTitle("TEST CANCELLED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "The run was stopped manually. Results are discarded — they would not be valid.",
                style = TextStyle(color = Forge.ink1, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS", onDismiss)
        }
    }
}

// ── Server Init ───────────────────────────────────────────────────────────────

@Composable
fun ServerInitOverlay() {
    OverlayContainer {
        OverlayCard(width = 280) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Forge.phasePreFlight, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                MonoTitle("CONNECTING", size = 12f)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Initializing secure session for leaderboard verification…",
                    style = TextStyle(color = Forge.ink1, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
                )
            }
        }
    }
}

// ── Server Error ──────────────────────────────────────────────────────────────

@Composable
fun ServerErrorOverlay(errorMessage: String, onCancel: () -> Unit, onRunOffline: () -> Unit) {
    OverlayContainer {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RingBadge(Ramp.at(0.5f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.width(3.dp).height(12.dp).background(Ramp.at(0.5f), RoundedCornerShape(1.5.dp)))
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.size(3.dp).clip(CircleShape).background(Ramp.at(0.5f)))
                    }
                }
                Spacer(Modifier.height(12.dp))
                MonoTitle("SESSION FAILED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Could not reach the diagnostic server:\n$errorMessage\n\nRun offline instead? (Offline runs can't be submitted.)",
                style = TextStyle(color = Forge.ink1, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("CANCEL", onCancel) }
                Box(Modifier.weight(1f)) { PrimaryButton("RUN OFFLINE", onRunOffline) }
            }
        }
    }
}

// ── Connection Request ─────────────────────────────────────────────────────────

@Composable
fun ConnectionRequestOverlay(onTurnedOn: () -> Unit, onSubmitLater: () -> Unit) {
    OverlayContainer {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                RingBadge(Forge.phasePreFlight) {
                    Box(Modifier.size(24.dp).border(2.dp, Forge.phasePreFlight, CircleShape)) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape).background(Forge.phasePreFlight).align(Alignment.Center)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                MonoTitle("DIAGNOSTICS COMPLETE")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Enable Wi-Fi or cellular now to submit your score to the global leaderboard.",
                style = TextStyle(color = Forge.ink1, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("I TURNED IT ON", onTurnedOn)
                SecondaryButton("SUBMIT LATER", onSubmitLater)
            }
        }
    }
}
