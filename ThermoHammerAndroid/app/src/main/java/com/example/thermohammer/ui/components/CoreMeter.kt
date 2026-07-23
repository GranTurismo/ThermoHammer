package com.example.thermohammer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@Composable
fun CoreMeter(
    index: Int,
    impact: Float,
    modifier: Modifier = Modifier
) {
    val animatedImpact by animateFloatAsState(
        targetValue = impact,
        animationSpec = tween(durationMillis = 500, easing = EaseInOut),
        label = "core_impact_$index"
    )

    val isIdle = animatedImpact <= 0.5f

    val ringColor = when {
        isIdle                -> Color.White.copy(alpha = 0.15f)
        animatedImpact >= 95f -> Color(0xFF33CC66)
        animatedImpact >= 80f -> Color(0xFFF2C94C)
        else                  -> Color(0xFFE85520)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val strokeWidth = 6.dp.toPx()
                val radius = (min(size.width, size.height) - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)

                // Track arc
                drawArc(
                    color = Color.White.copy(alpha = 0.06f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active arc
                if (!isIdle) {
                    val sweep = maxOf(0.05f, animatedImpact / 100f) * 360f
                    val gradient = Brush.sweepGradient(
                        colors = listOf(ringColor, ringColor.copy(alpha = 0.6f)),
                        center = center
                    )
                    drawArc(
                        brush = gradient,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // Center text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CORE $index",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (isIdle) {
                    Text(
                        text = "IDLE",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else if (animatedImpact >= 99.5f) {
                    Text(
                        text = "100%",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else {
                    val loss = (100f - animatedImpact).toInt()
                    Text(
                        text = "-$loss%",
                        style = TextStyle(
                            color = ringColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "IMPACT",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun GpuMeter(
    impact: Float,
    modifier: Modifier = Modifier
) {
    val animatedImpact by animateFloatAsState(
        targetValue = impact,
        animationSpec = tween(durationMillis = 500, easing = EaseInOut),
        label = "gpu_impact"
    )

    val isIdle = animatedImpact <= 0.5f
    val purpleColor = Color(0xFFAB5BFF)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFAB5BFF).copy(alpha = 0.05f))
            .border(1.dp, Color(0xFFAB5BFF).copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
            Canvas(modifier = Modifier.size(72.dp)) {
                val strokeWidth = 6.dp.toPx()
                val radius = (min(size.width, size.height) - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val topLeft = Offset(center.x - radius, center.y - radius)

                drawArc(
                    color = Color.White.copy(alpha = 0.06f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (!isIdle) {
                    val sweep = maxOf(0.05f, animatedImpact / 100f) * 360f
                    drawArc(
                        color = purpleColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "GPU CORE",
                    style = TextStyle(
                        color = Color(0xFFAB5BFF),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (isIdle) {
                    Text(
                        text = "IDLE",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else if (animatedImpact >= 99.5f) {
                    Text(
                        text = "100%",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                } else {
                    val loss = (100f - animatedImpact).toInt()
                    Text(
                        text = "-$loss%",
                        style = TextStyle(
                            color = purpleColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "IMPACT",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 7.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun CoreStatusView(coreImpacts: List<Float>, gpuImpact: Float = 0f, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF141414))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⬡  HARDWARE RELATIVE IMPACT",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
        ) {
            item {
                GpuMeter(impact = gpuImpact)
            }
            items(coreImpacts.size) { i ->
                CoreMeter(index = i + 1, impact = coreImpacts[i])
            }
        }
    }
}
