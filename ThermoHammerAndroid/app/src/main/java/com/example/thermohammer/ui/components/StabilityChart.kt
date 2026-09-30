package com.example.thermohammer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.example.thermohammer.engine.Attribution
import com.example.thermohammer.engine.StampV2
import com.example.thermohammer.engine.StabilityPoint
import com.example.thermohammer.engine.ThermalEvent
import com.example.thermohammer.engine.ThermalState
import com.example.thermohammer.ui.theme.*
import java.util.Locale

// ── Compatibility helpers (mapped onto the Forge system) ──────────────────────

fun thermalColor(state: ThermalState): Color = thermalStateColor(state)

fun thermalName(state: ThermalState): String = when (state) {
    ThermalState.NOMINAL  -> "NOMINAL"
    ThermalState.FAIR     -> "FAIR"
    ThermalState.SERIOUS  -> "SERIOUS (THROTTLED)"
    ThermalState.CRITICAL -> "CRITICAL"
}

fun stabilityColor(score: Float): Color = Ramp.forCapacity(score)

// ─────────────────────────────────────────────────────────────────────────────
// THE THROUGHPUT RIBBON
//
//  · band thickness = per-cluster throughput spread (migration asymmetry)
//  · dashed "potential" line at 100% — the gap below it IS the throttle loss
//  · ember underfill + ◇ onset marker once throttling is confirmed (3s <90%)
//  · ribbon edge tinted by dominant attribution — the *why* without a legend
//  · thermal events as notches on the axis
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StabilityChart(
    points: List<StabilityPoint>,
    events: List<ThermalEvent>,
    modifier: Modifier = Modifier,
    stamps: List<StampV2> = emptyList(),
    baselineIps: Double = 0.0,
    preRunIps: List<Double> = emptyList(),
    preRunMarkIdx: Int = 0,
    gpuBaselineFps: Double = 0.0,
    preRunGpuFps: List<Double> = emptyList()
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var chartWidthPx by remember { mutableFloatStateOf(1f) }

    // Time domain: pre-run samples occupy [-preRunSec, 0), measured at [0, lastTime].
    // Animated so each new stamp glides in rather than snapping the axis.
    val preRunSec = preRunIps.size * 0.25f
    val domainMin = -preRunSec
    val domainMax = maxOf(1f, points.lastOrNull()?.time ?: 0f)
    val domainMinAnim by animateFloatAsState(domainMin, tween(320, easing = FastOutSlowInEasing), label = "domain_min")
    val domainMaxAnim by animateFloatAsState(domainMax, tween(320, easing = FastOutSlowInEasing), label = "domain_max")
    val domainRange = maxOf(0.001f, domainMaxAnim - domainMinAnim)

    // Reference for normalizing pre-run throughput before the baseline is locked:
    // p95 of calibration-region samples (or running max during warm-up).
    val preRef = remember(preRunIps, baselineIps, preRunMarkIdx) {
        if (baselineIps > 0) baselineIps else {
            val region = if (preRunIps.size > preRunMarkIdx && preRunMarkIdx >= 0)
                preRunIps.drop(preRunMarkIdx) else preRunIps
            if (region.isEmpty()) 1.0 else {
                val s = region.sorted()
                s[(s.size * 0.95).toInt().coerceIn(0, s.size - 1)].coerceAtLeast(1.0)
            }
        }
    }

    val selectedPoint = remember(touchX, points, chartWidthPx, domainMinAnim, domainRange) {
        val tx = touchX ?: return@remember null
        if (points.isEmpty()) return@remember null
        val w = maxOf(1f, chartWidthPx)
        points.minByOrNull { pt ->
            kotlin.math.abs(((pt.time - domainMinAnim) / domainRange) * w - tx)
        }
    }

    // Debounced onset index (3 consecutive <90%)
    val onsetIdx = remember(points) {
        var idx: Int? = null
        for (i in 0 until points.size - 2) {
            if (points[i].score < 90f && points[i + 1].score < 90f && points[i + 2].score < 90f) {
                idx = i; break
            }
        }
        idx
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Forge.surface)
            .cornerTicks(Forge.ink2.copy(alpha = 0.5f))
            .border(1.dp, Forge.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("THROUGHPUT RIBBON", style = ThermoType.title(10f))
            if (touchX != null && selectedPoint != null) {
                val pt = selectedPoint
                val tSec = pt.time.toInt()
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Forge.phaseMeasured.copy(alpha = 0.12f))
                        .border(1.dp, Forge.phaseMeasured.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "%dm:%02ds".format(tSec / 60, tSec % 60),
                        style = ThermoType.data(10f, Forge.phaseMeasured, FontWeight.Bold)
                    )
                    Text(
                        "%.1f%%".format(Locale.US, pt.score),
                        style = ThermoType.data(10f, Ramp.forCapacity(pt.score), FontWeight.Black)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .onGloballyPositioned { chartWidthPx = it.size.width.toFloat() }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectDragGestures(
                            onDragStart = { offset -> touchX = offset.x },
                            onDrag = { change, _ -> touchX = change.position.x },
                            onDragEnd = { touchX = null },
                            onDragCancel = { touchX = null }
                        )
                    }
                    .pointerInput(points) {
                        detectTapGestures(
                            onPress = { offset ->
                                touchX = offset.x
                                tryAwaitRelease()
                                touchX = null
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                // Hairline grid at 25/50/75%
                listOf(0.25f, 0.50f, 0.75f).forEach { f ->
                    drawLine(Forge.hairline.copy(alpha = 0.5f), Offset(0f, h * f), Offset(w, h * f), 1f)
                }

                // ── Potential line — the baseline the run calibrated ────────
                drawLine(
                    color = Forge.ink1.copy(alpha = 0.45f),
                    start = Offset(0f, 0f), end = Offset(w, 0f),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )

                fun getX(t: Float) = ((t - domainMinAnim) / domainRange) * w
                fun getY(score: Float) = h - (score.coerceIn(0f, 110f) / 110f) * h

                // ── Ghost prefix: warm-up + calibration trace ──────────────
                // The full honest arc — JIT/scheduler ramp, boost peak, and the
                // collapse that happens BEFORE the measured run starts.
                if (preRunIps.size > 1) {
                    // pre-run region tint + t=0 divider
                    drawRect(
                        Forge.ink2.copy(alpha = 0.05f),
                        topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(getX(0f), h)
                    )
                    drawLine(
                        Forge.ink1.copy(alpha = 0.5f),
                        Offset(getX(0f), 0f), Offset(getX(0f), h), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                    // mark boundary between warm-up and calibration
                    val markX = getX(-(preRunIps.size - preRunMarkIdx) * 0.25f)
                    drawLine(
                        Forge.phaseCalibration.copy(alpha = 0.3f),
                        Offset(markX, 0f), Offset(markX, h), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f), 0f)
                    )
                    for (i in 0 until preRunIps.size - 1) {
                        val t0 = -(preRunIps.size - i) * 0.25f
                        val t1 = -(preRunIps.size - i - 1) * 0.25f
                        val s0 = (preRunIps[i] / preRef * 100).toFloat()
                        val s1 = (preRunIps[i + 1] / preRef * 100).toFloat()
                        val col = if (i >= preRunMarkIdx)
                            Forge.phaseCalibration.copy(alpha = 0.85f)   // calibration
                        else Forge.phaseWarmup.copy(alpha = 0.6f)        // warm-up
                        drawLine(
                            color = col,
                            start = Offset(getX(t0), getY(s0)),
                            end = Offset(getX(t1), getY(s1)),
                            strokeWidth = 2.5f, cap = StrokeCap.Round
                        )
                    }
                }

                if (points.isEmpty()) return@Canvas

                // ── Ribbon band: thickness = per-cluster spread ─────────────
                fun spread(i: Int): Float {
                    val st = stamps.getOrNull(i) ?: return 0f
                    val cl = st.ipsPerCluster
                    if (cl.size < 2 || st.ipsTotal <= 0) return 0f
                    val mx = cl.maxOrNull() ?: return 0f
                    val mn = cl.minOrNull() ?: return 0f
                    // spread % of total ips → score units
                    return ((mx - mn).toFloat() / st.ipsTotal.toFloat()) * 55f
                }

                // Ember scorch region under the post-onset curve
                onsetIdx?.let { oi ->
                    if (oi < points.size - 1) {
                        val scorch = Path()
                        scorch.moveTo(getX(points[oi].time), h)
                        for (i in oi until points.size) {
                            scorch.lineTo(getX(points[i].time), getY(points[i].score))
                        }
                        scorch.lineTo(getX(points.last().time), h)
                        scorch.close()
                        drawPath(
                            scorch,
                            Brush.verticalGradient(
                                0f to Ramp.at(0.68f).copy(alpha = 0.20f),
                                1f to Ramp.at(0.85f).copy(alpha = 0.05f),
                                startY = 0f, endY = h
                            )
                        )
                    }
                }

                // Band fill
                if (points.size > 1) {
                    val band = Path()
                    band.moveTo(getX(points[0].time), getY(points[0].score + spread(0) / 2))
                    for (i in 1 until points.size) {
                        band.lineTo(getX(points[i].time), getY(points[i].score + spread(i) / 2))
                    }
                    for (i in points.size - 1 downTo 0) {
                        band.lineTo(getX(points[i].time), getY(points[i].score - spread(i) / 2))
                    }
                    band.close()
                    drawPath(
                        band,
                        Brush.verticalGradient(
                            0f to Forge.phaseMeasured.copy(alpha = 0.10f),
                            1f to Ramp.at(0.62f).copy(alpha = 0.12f),
                            startY = 0f, endY = h
                        )
                    )
                }

                // Edge stroke — segmented, colored by dominant attribution
                for (i in 0 until points.size - 1) {
                    val attr = stamps.getOrNull(i)?.let { Attribution.of(it.attribution) } ?: Attribution.NONE
                    val edge = if (attr == Attribution.NONE)
                        Ramp.forCapacity((points[i].score + points[i + 1].score) / 2)
                    else attributionColor(attr)
                    drawLine(
                        color = edge,
                        start = Offset(getX(points[i].time), getY(points[i].score)),
                        end = Offset(getX(points[i + 1].time), getY(points[i + 1].score)),
                        strokeWidth = 4f, cap = StrokeCap.Round
                    )
                }
                if (points.size == 1) {
                    drawLine(Ramp.forCapacity(points[0].score), Offset(0f, getY(points[0].score)), Offset(w, getY(points[0].score)), 4f)
                }

                // ── GPU channel trace — dashed violet, % of its own baseline ──
                // Drawn when stamps carry fps (COMBINED mode); in GPU-only mode
                // the ribbon itself already IS the gpu curve.
                if (gpuBaselineFps > 0 && stamps.any { it.gpuFps >= 0 }) {
                    for (i in 0 until points.size - 1) {
                        val s0 = stamps.getOrNull(i)?.gpuFps ?: continue
                        val s1 = stamps.getOrNull(i + 1)?.gpuFps ?: continue
                        if (s0 < 0 || s1 < 0) continue
                        drawLine(
                            color = Forge.phaseCalibration.copy(alpha = 0.85f),
                            start = Offset(getX(points[i].time), getY((s0 / gpuBaselineFps * 100).toFloat())),
                            end = Offset(getX(points[i + 1].time), getY((s1 / gpuBaselineFps * 100).toFloat())),
                            strokeWidth = 2.5f, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                        )
                    }
                    // GPU ghost prefix — same -32s domain as the CPU trace
                    if (preRunGpuFps.size > 1) {
                        for (i in 0 until preRunGpuFps.size - 1) {
                            val t0 = -(preRunGpuFps.size - i) * 0.25f
                            val t1 = -(preRunGpuFps.size - i - 1) * 0.25f
                            drawLine(
                                color = Forge.phaseCalibration.copy(alpha = 0.35f),
                                start = Offset(getX(t0), getY((preRunGpuFps[i] / gpuBaselineFps * 100).toFloat())),
                                end = Offset(getX(t1), getY((preRunGpuFps[i + 1] / gpuBaselineFps * 100).toFloat())),
                                strokeWidth = 2f, cap = StrokeCap.Round
                            )
                        }
                    }
                }

                // ── Live head pip — the breathing tip of the ribbon ─────────
                if (points.size > 1) {
                    val lp = points.last()
                    val hx = getX(lp.time); val hy = getY(lp.score)
                    val headCol = stamps.lastOrNull()?.let { Attribution.of(it.attribution) }
                        ?.takeIf { it != Attribution.NONE }?.let { attributionColor(it) }
                        ?: Ramp.forCapacity(lp.score)
                    drawCircle(headCol.copy(alpha = 0.22f), 10f, Offset(hx, hy))
                    drawCircle(headCol, 4.5f, Offset(hx, hy))
                    drawCircle(Forge.ink0, 2f, Offset(hx, hy))
                }

                // ── Onset diamond ────────────────────────────────────────────
                onsetIdx?.let { oi ->
                    val ox = getX(points[oi].time); val oy = getY(points[oi].score)
                    val d = Path().apply {
                        moveTo(ox, oy - 10f); lineTo(ox + 7f, oy); lineTo(ox, oy + 10f); lineTo(ox - 7f, oy); close()
                    }
                    drawPath(d, Ramp.at(0.68f))
                    drawPath(d, Forge.bg, style = Stroke(1.5f))
                }

                // ── Thermal event notches on the axis ───────────────────────
                for (event in events) {
                    val x = getX(event.time)
                    drawLine(
                        color = thermalStateColor(event.state).copy(alpha = 0.8f),
                        start = Offset(x, h - 8f), end = Offset(x, h),
                        strokeWidth = 3f
                    )
                }

                // ── Touch inspector ─────────────────────────────────────────
                touchX?.let { tx ->
                    val cx = tx.coerceIn(0f, w)
                    val nearest = points.minByOrNull { kotlin.math.abs(getX(it.time) - cx) }
                    nearest?.let { p ->
                        val txp = getX(p.time); val typ = getY(p.score)
                        drawLine(
                            Forge.phaseMeasured.copy(alpha = 0.7f),
                            Offset(txp, 0f), Offset(txp, h), 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                        drawCircle(Forge.phaseMeasured.copy(alpha = 0.25f), 12f, Offset(txp, typ))
                        drawCircle(Forge.phaseMeasured, 5f, Offset(txp, typ))
                        drawCircle(Forge.ink0, 2.5f, Offset(txp, typ))
                    }
                }
            }
        }

        // X-axis
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (preRunIps.size > 1) "-%ds PRE-RUN".format(preRunSec.toInt()) else "0m:00s",
                style = ThermoType.annotation(9f, Forge.phaseCalibration.copy(alpha = 0.8f))
            )
            Spacer(Modifier.weight(1f))
            if (preRunIps.size > 1) {
                Text("▸ MEASURED", style = ThermoType.annotation(8f, Forge.phaseMeasured.copy(alpha = 0.7f)))
                Spacer(Modifier.weight(1f))
            }
            val lastTime = points.lastOrNull()?.time?.toInt() ?: 0
            Text("%dm:%02ds".format(lastTime / 60, lastTime % 60), style = ThermoType.annotation(9f))
        }
    }
}

// ── Dual comparison chart — teal vs violet per the design spec ────────────────

@Composable
fun DualStabilityChart(
    pointsA: List<StabilityPoint>,
    labelA: String,
    pointsB: List<StabilityPoint>,
    labelB: String,
    modifier: Modifier = Modifier
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var selectedA by remember { mutableStateOf<StabilityPoint?>(null) }
    var selectedB by remember { mutableStateOf<StabilityPoint?>(null) }

    val colorA = Forge.phaseMeasured
    val colorB = Forge.phaseCalibration

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Forge.surface)
            .cornerTicks(Forge.ink2.copy(alpha = 0.5f))
            .border(1.dp, Forge.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("DUAL RUN COMPARISON", style = ThermoType.title(10f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colorA))
                    Text(labelA, style = ThermoType.data(9f, colorA, FontWeight.Bold))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colorB))
                    Text(labelB, style = ThermoType.data(9f, colorB, FontWeight.Bold))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pointsA, pointsB) {
                        detectDragGestures(
                            onDragStart = { offset -> touchX = offset.x },
                            onDrag = { change, _ -> touchX = change.position.x },
                            onDragEnd = { touchX = null; selectedA = null; selectedB = null },
                            onDragCancel = { touchX = null; selectedA = null; selectedB = null }
                        )
                    }
                    .pointerInput(pointsA, pointsB) {
                        detectTapGestures(
                            onPress = { offset ->
                                touchX = offset.x
                                tryAwaitRelease()
                                touchX = null; selectedA = null; selectedB = null
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                listOf(0.25f, 0.50f, 0.75f).forEach { f ->
                    drawLine(Forge.hairline.copy(alpha = 0.5f), Offset(0f, h * f), Offset(w, h * f), 1f)
                }

                // Shared potential line
                drawLine(
                    Forge.ink1.copy(alpha = 0.35f), Offset(0f, 0f), Offset(w, 0f), 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )

                val maxTime = maxOf(1f, maxOf(pointsA.lastOrNull()?.time ?: 1f, pointsB.lastOrNull()?.time ?: 1f))
                fun getX(t: Float) = (t / maxTime) * w
                fun getY(s: Float) = h - (s.coerceIn(0f, 110f) / 110f) * h

                fun drawCurve(pts: List<StabilityPoint>, color: Color) {
                    if (pts.isEmpty()) return
                    val path = Path()
                    path.moveTo(getX(pts[0].time), getY(pts[0].score))
                    for (i in 0 until pts.size - 1) {
                        val x0 = getX(pts[i].time); val y0 = getY(pts[i].score)
                        val x1 = getX(pts[i + 1].time); val y1 = getY(pts[i + 1].score)
                        val cx = x0 + (x1 - x0) / 2f
                        path.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawCurve(pointsA, colorA)
                drawCurve(pointsB, colorB)

                touchX?.let { tx ->
                    val cx = tx.coerceIn(0f, w)
                    val tA = pointsA.minByOrNull { kotlin.math.abs(getX(it.time) - cx) }
                    val tB = pointsB.minByOrNull { kotlin.math.abs(getX(it.time) - cx) }
                    selectedA = tA; selectedB = tB
                    drawLine(
                        Forge.ink1.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                    tA?.let { p -> drawCircle(colorA, 5f, Offset(getX(p.time), getY(p.score))); drawCircle(Forge.ink0, 2.5f, Offset(getX(p.time), getY(p.score))) }
                    tB?.let { p -> drawCircle(colorB, 5f, Offset(getX(p.time), getY(p.score))); drawCircle(Forge.ink0, 2.5f, Offset(getX(p.time), getY(p.score))) }
                }
            }
        }

        if (touchX != null && (selectedA != null || selectedB != null)) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Forge.raised)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedA?.let { p ->
                    val s = p.time.toInt()
                    Text("$labelA %02d:%02d → %.1f%%".format(s / 60, s % 60, p.score), style = ThermoType.data(10f, colorA, FontWeight.Bold))
                }
                selectedB?.let { p ->
                    val s = p.time.toInt()
                    Text("$labelB %02d:%02d → %.1f%%".format(s / 60, s % 60, p.score), style = ThermoType.data(10f, colorB, FontWeight.Bold))
                }
            }
        }
    }
}
