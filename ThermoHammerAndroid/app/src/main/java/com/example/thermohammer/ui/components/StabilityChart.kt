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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.StabilityPoint
import com.example.thermohammer.engine.ThermalEvent
import com.example.thermohammer.engine.ThermalState
import java.util.Locale

// ── Color Helpers ─────────────────────────────────────────────────────────────

fun thermalColor(state: ThermalState): Color = when (state) {
    ThermalState.NOMINAL  -> Color(0xFF33CC66)
    ThermalState.FAIR     -> Color(0xFFF2C94C)
    ThermalState.SERIOUS  -> Color(0xFFF2994A)
    ThermalState.CRITICAL -> Color(0xFFEB5757)
}

fun thermalName(state: ThermalState): String = when (state) {
    ThermalState.NOMINAL  -> "NOMINAL"
    ThermalState.FAIR     -> "FAIR"
    ThermalState.SERIOUS  -> "SERIOUS (THROTTLED)"
    ThermalState.CRITICAL -> "CRITICAL"
}

fun stabilityColor(score: Float): Color = when {
    score >= 90f -> Color(0xFF33CC66)
    score >= 75f -> Color(0xFFF2C94C)
    else         -> Color(0xFFE85520)
}

// ── Interactive Single Stability Chart ───────────────────────────────────────

@Composable
fun StabilityChart(
    points: List<StabilityPoint>,
    events: List<ThermalEvent>,
    modifier: Modifier = Modifier
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var chartWidthPx by remember { mutableFloatStateOf(1f) }

    val selectedPoint = remember(touchX, points, chartWidthPx) {
        val tx = touchX ?: return@remember null
        if (points.isEmpty()) return@remember null
        val firstTime = points.first().time
        val lastTime = maxOf(1f, points.last().time)
        val totalRange = lastTime - firstTime
        val w = maxOf(1f, chartWidthPx)
        points.minByOrNull { pt ->
            val ptX = if (totalRange == 0f) 0f else (((pt.time - firstTime) / totalRange) * w)
            kotlin.math.abs(ptX - tx)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        // Header with Touch Point Info Display
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "▦  UNIFIED STABILITY CHART",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )

            // Touch Stamp Badge Header (Disappears when touch is released)
            if (touchX != null && selectedPoint != null) {
                val pt = selectedPoint
                val tSec = pt.time.toInt()
                val minStr = tSec / 60
                val secStr = "%02d".format(tSec % 60)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${minStr}m:${secStr}s",
                        style = TextStyle(color = Color(0xFF00E5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "SCORE: %.1f%%".format(Locale.US, pt.score),
                        style = TextStyle(color = stabilityColor(pt.score), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Chart Canvas with Touch Drag / Release Gestures
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
                chartWidthPx = w

                // Grid lines at 25%, 50%, 75%
                listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
                    val y = h * fraction
                    drawLine(
                        color = Color.White.copy(alpha = 0.04f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                if (points.isEmpty()) return@Canvas

                val firstTime = points.first().time
                val lastTime = maxOf(1f, points.last().time)
                val totalRange = lastTime - firstTime

                fun getX(time: Float): Float {
                    if (totalRange == 0f) return 0f
                    return ((time - firstTime) / totalRange) * w
                }
                fun getY(score: Float): Float = h - (score / 100f) * h

                // Line gradient
                val lineGradient = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF33CC66),
                        0.25f to Color(0xFFF2C94C),
                        0.5f to Color(0xFFE85520),
                        1.0f to Color(0xFFD93025)
                    ),
                    startY = 0f, endY = h
                )

                // Fill gradient
                val fillGradient = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF33CC66).copy(alpha = 0.15f),
                        0.5f to Color(0xFFE85520).copy(alpha = 0.06f),
                        1.0f to Color.Transparent
                    ),
                    startY = 0f, endY = h
                )

                val path = Path()
                val fillPath = Path()

                fillPath.moveTo(getX(points.first().time), h)
                fillPath.lineTo(getX(points.first().time), getY(points.first().score))

                if (points.size == 1) {
                    path.moveTo(0f, getY(points[0].score))
                    path.lineTo(w, getY(points[0].score))
                    fillPath.lineTo(w, getY(points[0].score))
                    fillPath.lineTo(w, h)
                    fillPath.close()
                } else {
                    path.moveTo(getX(points[0].time), getY(points[0].score))
                    for (i in 0 until points.size - 1) {
                        val x0 = getX(points[i].time); val y0 = getY(points[i].score)
                        val x1 = getX(points[i+1].time); val y1 = getY(points[i+1].score)
                        val cx = x0 + (x1 - x0) / 2f
                        path.cubicTo(cx, y0, cx, y1, x1, y1)
                        fillPath.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    val last = points.last()
                    fillPath.lineTo(getX(last.time), h)
                    fillPath.close()
                }

                drawPath(fillPath, fillGradient)
                drawPath(path, lineGradient, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Thermal event markers
                for (event in events) {
                    val xPos = getX(event.time)
                    val color = thermalColor(event.state)
                    val dotLen = 6f; val gap = 4f
                    var y = 24f
                    while (y < h) {
                        drawLine(color.copy(alpha = 0.5f), Offset(xPos, y), Offset(xPos, minOf(y + dotLen, h)), 1f)
                        y += dotLen + gap
                    }
                }

                // Touch Inspector Cursor Line and Highlight Node (Only drawn while touching)
                touchX?.let { tx ->
                    val clampedX = tx.coerceIn(0f, w)
                    val nearest = points.minByOrNull { kotlin.math.abs(getX(it.time) - clampedX) }
                    nearest?.let { targetPt ->
                        val targetX = getX(targetPt.time)
                        val targetY = getY(targetPt.score)

                        // Vertical selector line
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.7f),
                            start = Offset(targetX, 0f),
                            end = Offset(targetX, h),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )

                        // Glowing Node Outer Aura
                        drawCircle(
                            color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                            radius = 12f,
                            center = Offset(targetX, targetY)
                        )

                        // Glowing Node Inner Solid Circle
                        drawCircle(
                            color = Color(0xFF00E5FF),
                            radius = 5f,
                            center = Offset(targetX, targetY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2.5f,
                            center = Offset(targetX, targetY)
                        )
                    }
                }
            }
        }

        // X-axis labels
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("0m:00s", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
            Spacer(Modifier.weight(1f))
            val lastTime = points.lastOrNull()?.time?.toInt() ?: 0
            Text(
                "${lastTime / 60}m:${"%02d".format(lastTime % 60)}s",
                style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            )
        }
    }
}

// ── Dual Stability Chart for Client-Side Run Comparison ─────────────────────

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

    val colorA = Color(0xFF00E5FF) // Cyan
    val colorB = Color(0xFFFFAB00) // Amber

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        // Legend & Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📊  DUAL STABILITY COMPARISON",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colorA))
                    Text(labelA, style = TextStyle(color = colorA, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colorB))
                    Text(labelB, style = TextStyle(color = colorB, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Canvas overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
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

                // Grid lines
                listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
                    val y = h * fraction
                    drawLine(color = Color.White.copy(alpha = 0.04f), start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }

                val maxTimeA = pointsA.lastOrNull()?.time ?: 1f
                val maxTimeB = pointsB.lastOrNull()?.time ?: 1f
                val totalMaxTime = maxOf(1f, maxOf(maxTimeA, maxTimeB))

                fun getX(time: Float): Float = (time / totalMaxTime) * w
                fun getY(score: Float): Float = h - (score / 100f) * h

                // Helper to draw path
                fun drawCurve(pts: List<StabilityPoint>, color: Color) {
                    if (pts.isEmpty()) return
                    val path = Path()
                    path.moveTo(getX(pts[0].time), getY(pts[0].score))
                    for (i in 0 until pts.size - 1) {
                        val x0 = getX(pts[i].time); val y0 = getY(pts[i].score)
                        val x1 = getX(pts[i+1].time); val y1 = getY(pts[i+1].score)
                        val cx = x0 + (x1 - x0) / 2f
                        path.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                // Draw curves
                drawCurve(pointsA, colorA)
                drawCurve(pointsB, colorB)

                // Interactive touch vertical line & data nodes (Resets when touch released)
                touchX?.let { tx ->
                    val clampedX = tx.coerceIn(0f, w)
                    val targetA = pointsA.minByOrNull { kotlin.math.abs(getX(it.time) - clampedX) }
                    val targetB = pointsB.minByOrNull { kotlin.math.abs(getX(it.time) - clampedX) }

                    selectedA = targetA
                    selectedB = targetB

                    // Vertical dashed indicator
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(clampedX, 0f),
                        end = Offset(clampedX, h),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )

                    targetA?.let { pA ->
                        val xA = getX(pA.time); val yA = getY(pA.score)
                        drawCircle(colorA, radius = 5f, center = Offset(xA, yA))
                        drawCircle(Color.White, radius = 2.5f, center = Offset(xA, yA))
                    }

                    targetB?.let { pB ->
                        val xB = getX(pB.time); val yB = getY(pB.score)
                        drawCircle(colorB, radius = 5f, center = Offset(xB, yB))
                        drawCircle(Color.White, radius = 2.5f, center = Offset(xB, yB))
                    }
                }
            }
        }

        // Live Selected Data Bar (Only visible while touching)
        if (touchX != null && (selectedA != null || selectedB != null)) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedA?.let { pA ->
                    val sec = pA.time.toInt()
                    Text(
                        "$labelA: %02d:%02d -> %.1f%%".format(sec / 60, sec % 60, pA.score),
                        style = TextStyle(color = colorA, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    )
                }
                selectedB?.let { pB ->
                    val sec = pB.time.toInt()
                    Text(
                        "$labelB: %02d:%02d -> %.1f%%".format(sec / 60, sec % 60, pB.score),
                        style = TextStyle(color = colorB, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
