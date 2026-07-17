package com.example.thermohammer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.StabilityPoint
import com.example.thermohammer.engine.ThermalEvent
import com.example.thermohammer.engine.ThermalState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

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

// ── Stability Chart ────────────────────────────────────────────────────────────

@Composable
fun StabilityChart(
    points: List<StabilityPoint>,
    events: List<ThermalEvent>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "▦  UNIFIED STABILITY CHART",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(Modifier.height(12.dp))

        // Chart Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            val w = size.width
            val h = size.height

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

            // Line gradient (green → amber → orange → red, top to bottom)
            val lineGradient = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFF33CC66),
                    0.25f to Color(0xFFF2C94C),
                    0.5f to Color(0xFFE85520),
                    1.0f to Color(0xFFD93025)
                ),
                startY = 0f,
                endY = h
            )

            // Fill gradient
            val fillGradient = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFF33CC66).copy(alpha = 0.15f),
                    0.5f to Color(0xFFE85520).copy(alpha = 0.06f),
                    1.0f to Color.Transparent
                ),
                startY = 0f,
                endY = h
            )

            // Build bezier path
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
                    val x0 = getX(points[i].time);    val y0 = getY(points[i].score)
                    val x1 = getX(points[i+1].time); val y1 = getY(points[i+1].score)
                    val cx = x0 + (x1 - x0) / 2f
                    path.cubicTo(cx, y0, cx, y1, x1, y1)
                    fillPath.cubicTo(cx, y0, cx, y1, x1, y1)
                }
                val last = points.last()
                fillPath.lineTo(getX(last.time), h)
                fillPath.close()
            }

            // Draw fill
            drawPath(fillPath, fillGradient)
            // Draw line
            drawPath(path, lineGradient, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Thermal event markers
            for (event in events) {
                val xPos = getX(event.time)
                val color = thermalColor(event.state)
                // Dotted vertical line
                val dotLen = 6f; val gap = 4f
                var y = 24f
                while (y < h) {
                    drawLine(color.copy(alpha = 0.5f), Offset(xPos, y), Offset(xPos, minOf(y + dotLen, h)), 1f)
                    y += dotLen + gap
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
