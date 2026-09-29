package com.example.thermohammer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.RunPhase
import com.example.thermohammer.ui.theme.*

// ── Phase Rail — persistent strip narrating the test's six acts ────────────────
//
// PRE-FLIGHT ▸ COOLDOWN ▸ WARM-UP ▸ CALIBRATION ▸ MEASURED ▸ VERDICT
// The current segment glows in its phase accent; completed segments stay lit dim.

private val PHASES = listOf(
    "PRE-FLIGHT", "COOLDOWN", "WARM-UP", "CALIBRATE", "MEASURED", "VERDICT"
)

private fun segmentColor(i: Int): Color = when (i) {
    0 -> Forge.phasePreFlight
    1 -> Forge.phaseCooldown
    2 -> Forge.phaseWarmup
    3 -> Forge.phaseCalibration
    4 -> Forge.phaseMeasured
    else -> Ramp.at(0.05f) // VERDICT
}

private fun railIndex(phase: RunPhase, preflightActive: Boolean, completed: Boolean): Int = when {
    completed -> 5
    phase == RunPhase.MEASURED -> 4
    phase == RunPhase.CALIBRATION -> 3
    phase == RunPhase.WARMUP -> 2
    phase == RunPhase.COOLDOWN -> 1
    preflightActive -> 0
    else -> -1
}

@Composable
fun PhaseRail(
    phase: RunPhase,
    preflightActive: Boolean = false,
    completed: Boolean = false,
    modifier: Modifier = Modifier,
    progress: Float = 0f,          // 0..1 within the CURRENT phase
    progressLabel: String = ""     // e.g. "T-04:12" or "45.2° → 40°"
) {
    val activeIdx = railIndex(phase, preflightActive, completed)
    val reduced = rememberReducedMotion()

    val pulse by rememberInfiniteTransition(label = "rail_pulse").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "rail_pulse_f"
    )
    val activeAlpha = if (reduced) 1f else pulse

    val fillColor = when {
        completed -> Ramp.at(0.05f)
        preflightActive -> Forge.phasePreFlight
        else -> phaseColor(phase)
    }
    val fillAnim by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(280, easing = FastOutSlowInEasing), label = "rail_fill"
    )
    val showBar = activeIdx >= 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Forge.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PHASES.forEachIndexed { i, name ->
            val isActive = i == activeIdx
            val segColor by animateColorAsState(
                targetValue = when {
                    i < activeIdx  -> segmentColor(i).copy(alpha = 0.55f)
                    i == activeIdx -> segmentColor(i)
                    else           -> Forge.ink2.copy(alpha = 0.30f)
                },
                animationSpec = tween(280), label = "seg_$i"
            )
            val lineColor by animateColorAsState(
                targetValue = if (i <= activeIdx) segmentColor(i).copy(alpha = 0.5f)
                              else Forge.ink2.copy(alpha = 0.18f),
                animationSpec = tween(280), label = "segline_$i"
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // left connector
                    Canvas(Modifier.weight(1f).height(1.dp)) {
                        if (i > 0) drawLine(
                            color = lineColor,
                            start = Offset(0f, size.height / 2),
                            end = Offset(size.width, size.height / 2),
                            strokeWidth = 1f
                        )
                    }
                    Canvas(Modifier.size(if (isActive) 7.dp else 5.dp)) {
                        drawCircle(segColor.copy(alpha = if (isActive) activeAlpha else 1f))
                    }
                    // right connector
                    Canvas(Modifier.weight(1f).height(1.dp)) {
                        if (i < PHASES.lastIndex) drawLine(
                            color = lineColor,
                            start = Offset(0f, size.height / 2),
                            end = Offset(size.width, size.height / 2),
                            strokeWidth = 1f
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    name,
                    style = TextStyle(
                        color = segColor.copy(alpha = if (isActive) activeAlpha else 1f),
                        fontSize = 6.5.sp,
                        fontFamily = InstrumentMono,
                        fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                )
            }
        }
    }

        // ── Live phase-progress strip ──────────────────────────────────────
        // Quarter-ticked track; fill = progress within the current phase only.
        if (showBar) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val trackH = size.height
                    // track
                    drawRoundRect(
                        color = Forge.ink2.copy(alpha = 0.18f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2)
                    )
                    // quarter ticks
                    for (q in 1..3) {
                        drawLine(
                            Forge.bg.copy(alpha = 0.9f),
                            Offset(size.width * q / 4f, 0f),
                            Offset(size.width * q / 4f, trackH),
                            strokeWidth = 1.5f
                        )
                    }
                    // fill
                    val fw = size.width * fillAnim
                    if (fw > 0f) {
                        drawRoundRect(
                            color = fillColor,
                            size = androidx.compose.ui.geometry.Size(fw, trackH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2)
                        )
                        // leading edge pip
                        drawCircle(fillColor, trackH * 0.9f, Offset(fw.coerceIn(trackH, size.width), trackH / 2))
                    }
                }
            }
            if (progressLabel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        PHASES.getOrElse(activeIdx.coerceAtLeast(0)) { "" },
                        style = TextStyle(
                            color = fillColor.copy(alpha = 0.8f), fontSize = 7.sp,
                            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        progressLabel,
                        style = TextStyle(
                            color = Forge.ink1, fontSize = 7.sp,
                            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}
