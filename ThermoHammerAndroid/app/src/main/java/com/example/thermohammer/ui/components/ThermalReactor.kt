package com.example.thermohammer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.*
import com.example.thermohammer.ui.theme.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// THE THERMAL REACTOR — hero instrument
//
//   outer ring   = one arc segment per CPU cluster, ticks = each core's cur/max
//   middle ring  = kernel thermal headroom — drains like coolant
//   center       = delivered-capacity numeral on the thermal ramp
//                  + throttle-onset ◇ marker + countdown / phase name
//   idle         = dormant wireframe breathing at 8%
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ThermalReactor(
    state: StressState,
    topology: ClusterTopology,
    modifier: Modifier = Modifier
) {
    val reduced = rememberReducedMotion()
    val running = state.isRunning
    val idle = !running && !state.wasCompleted

    // ── Live values ──────────────────────────────────────────────────────────
    val stability by animateFloatAsState(
        targetValue = state.overallStability,
        animationSpec = tween(280, easing = FastOutSlowInEasing), label = "reactor_value"
    )
    val headroom = (state.thermalHeadroom ?: 1f).coerceIn(0f, 1f)
    val headroomAnim by animateFloatAsState(headroom, tween(400), label = "reactor_headroom")

    // Per-cluster average freq ratio (cur / hw max) — lerped so arcs sweep
    // smoothly instead of jumping on each telemetry tick
    val clusterRatios: List<Float> = remember(state.cpuFrequencies, topology) {
        topology.clusters.map { cl ->
            val ratios = cl.mapNotNull { c -> state.cpuFrequencies.getOrNull(c)?.percentOfMax }
            if (ratios.isEmpty()) 0f else ratios.average().toFloat() / 100f
        }
    }
    val clusterRatiosAnim = clusterRatios.mapIndexed { i, r ->
        animateFloatAsState(r, tween(450, easing = FastOutSlowInEasing), label = "cl_ratio_$i").value
    }

    // Throttle-onset from stamps (debounced: 3 consecutive <90% of baseline)
    val onsetSec: Int? = remember(state.recordedStamps, state.baselineIps) {
        val b = state.baselineIps
        if (b <= 0 || state.recordedStamps.size < 3) null else {
            val stamps = state.recordedStamps
            var found: Int? = null
            for (i in 0 until stamps.size - 2) {
                if (stamps[i].ipsTotal < b * 0.9 &&
                    stamps[i + 1].ipsTotal < b * 0.9 &&
                    stamps[i + 2].ipsTotal < b * 0.9) {
                    found = stamps[i].elapsedMs / 1000; break
                }
            }
            found
        }
    }

    // ── Animation state ──────────────────────────────────────────────────────
    val breathe by rememberInfiniteTransition(label = "reactor_breathe").animateFloat(
        0.05f, 0.14f,
        infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val armSweep by rememberInfiniteTransition(label = "reactor_arm").animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "arm_sweep"
    )
    val arming = state.phase == RunPhase.COOLDOWN ||
            state.phase == RunPhase.WARMUP || state.phase == RunPhase.CALIBRATION
    val breathing = if (idle && !reduced) breathe else if (idle) 0.10f else 0f

    val rampColor = Ramp.forCapacity(stability)
    val phaseCol = phaseColor(state.phase)

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val R = size.minDimension / 2

            // ── Bloom behind instrument when hot/active ─────────────────────
            if (running && !idle) {
                bloom(c, R * 1.15f, rampColor, strength = (100f - stability).coerceIn(0f, 45f) / 100f * 0.5f + 0.06f)
            }

            // ── OUTER: cluster tick ring ────────────────────────────────────
            val outerR = R * 0.92f
            val gapDeg = 6f
            val nClusters = topology.clusters.size.coerceAtLeast(1)
            val segSweep = 360f / nClusters

            // dormant wireframe base ring
            drawCircle(
                color = Forge.hairline.copy(alpha = if (idle) 0.5f else 0.35f),
                radius = outerR, center = c,
                style = Stroke(width = 2f)
            )

            topology.clusters.forEachIndexed { ci, cores ->
                val startA = -90f + ci * segSweep + gapDeg / 2
                val sweep = segSweep - gapDeg
                val ratio = clusterRatiosAnim.getOrElse(ci) { 0f }.coerceIn(0f, 1f)

                if (idle) {
                    // wireframe tick arcs only
                    drawArc(
                        color = Forge.ink2.copy(alpha = 0.35f + breathing),
                        startAngle = startA, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(c.x - outerR, c.y - outerR),
                        size = Size(outerR * 2, outerR * 2),
                        style = Stroke(width = 2f)
                    )
                } else {
                    // track
                    drawArc(
                        color = Forge.hairline,
                        startAngle = startA, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(c.x - outerR, c.y - outerR),
                        size = Size(outerR * 2, outerR * 2),
                        style = Stroke(width = 7f, cap = StrokeCap.Butt)
                    )
                    // filled arc = cluster delivered clock
                    drawArc(
                        color = Ramp.at(1f - ratio).copy(alpha = if (arming) 0.55f else 1f),
                        startAngle = startA, sweepAngle = sweep * ratio, useCenter = false,
                        topLeft = Offset(c.x - outerR, c.y - outerR),
                        size = Size(outerR * 2, outerR * 2),
                        style = Stroke(width = 7f, cap = StrokeCap.Butt)
                    )
                    // per-core tick marks — length scaled by core's own ratio
                    cores.forEachIndexed { k, core ->
                        val f = state.cpuFrequencies.getOrNull(core)
                        val cr = ((f?.percentOfMax ?: 0) / 100f).coerceIn(0f, 1f)
                        val a = Math.toRadians((startA + (k + 0.5f) * (sweep / cores.size)).toDouble())
                        val inner = outerR - 14f
                        val outer = outerR - 14f - 8f * cr - 2f
                        drawLine(
                            color = Ramp.at(1f - cr).copy(alpha = 0.9f),
                            start = Offset((c.x + inner * cos(a)).toFloat(), (c.y + inner * sin(a)).toFloat()),
                            end   = Offset((c.x + outer * cos(a)).toFloat(), (c.y + outer * sin(a)).toFloat()),
                            strokeWidth = 2.5f, cap = StrokeCap.Round
                        )
                    }
                }
            }

            // ── MIDDLE: thermal headroom coolant arc ────────────────────────
            val midR = R * 0.70f
            drawCircle(Forge.hairline, radius = midR, center = c, style = Stroke(3f))
            if (!idle) {
                drawArc(
                    color = Ramp.at(1f - headroomAnim).copy(alpha = 0.9f),
                    startAngle = -90f, sweepAngle = 360f * headroomAnim, useCenter = false,
                    topLeft = Offset(c.x - midR, c.y - midR),
                    size = Size(midR * 2, midR * 2),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )
            }

            // ── Arming sweep (warmup/calibration radar line) ────────────────
            if (arming && !reduced) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.85f to Color.Transparent,
                        1f to phaseCol.copy(alpha = 0.8f),
                        center = c
                    ),
                    startAngle = armSweep - 90f, sweepAngle = 60f, useCenter = false,
                    topLeft = Offset(c.x - R * 0.80f, c.y - R * 0.80f),
                    size = Size(R * 1.60f, R * 1.60f),
                    style = Stroke(width = 2.5f)
                )
            }

            // ── Idle dormant wireframe ──────────────────────────────────────
            if (idle) {
                drawCircle(
                    color = Forge.ink2.copy(alpha = breathing + 0.10f),
                    radius = R * 0.55f, center = c, style = Stroke(1.5f)
                )
            }
        }

        // ── Center readout — crossfades between modes/phases ────────────────
        val centerMode = when {
            idle -> "idle"
            state.phase != RunPhase.MEASURED -> "phase:${state.phase.name}"
            else -> "run"
        }
        androidx.compose.animation.Crossfade(
            targetState = centerMode,
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "reactor_center"
        ) { mode ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                mode == "idle" -> {
                    Text(
                        "STANDBY",
                        style = TextStyle(
                            color = Forge.ink2.copy(alpha = 0.9f), fontSize = 15.sp,
                            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "THERMAL REACTOR",
                        style = ThermoType.annotation(8f)
                    )
                }
                mode != "run" -> {
                    Text(
                        state.phase.displayName,
                        style = TextStyle(
                            color = phaseCol, fontSize = 14.sp,
                            fontFamily = InstrumentMono, fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (state.phase) {
                            RunPhase.COOLDOWN -> {
                                val t = if (state.cpuTemp > 0f) "%.1f".format(state.cpuTemp) else "--"
                                "$t° → %.0f° · %ds".format(state.cooldownTargetC, state.cooldownElapsedSec)
                            }
                            RunPhase.WARMUP -> "settling JIT + governor"
                            else -> "locking baseline"
                        },
                        style = ThermoType.annotation(8f)
                    )
                }
                else -> {
                    Text(
                        "%.1f%%".format(stability),
                        style = TextStyle(
                            color = rampColor, fontSize = 42.sp,
                            fontFamily = DisplayFont, fontWeight = FontWeight.Medium
                        )
                    )
                    Text("DELIVERED CAPACITY", style = ThermoType.label(7.5f, Forge.ink2))
                    Spacer(Modifier.height(2.dp))
                    val total = state.testDuration.seconds ?: 300
                    val remaining = (total - state.elapsedSeconds).coerceAtLeast(0)
                    Text(
                        "T-%02d:%02d".format(remaining / 60, remaining % 60),
                        style = ThermoType.data(10f, Forge.ink1)
                    )
                    if (onsetSec != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "◇ ONSET %d:%02d".format(onsetSec / 60, onsetSec % 60),
                            style = TextStyle(
                                color = Ramp.at(0.68f), fontSize = 9.sp,
                                fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
        }
    }
}
