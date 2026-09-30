package com.example.thermohammer.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// GPU STAGE — full-screen live render while the 3D benchmark runs.
//
//   The SurfaceView attaches directly to GpuBench's GL thread: the scene is
//   always rendered into the fixed 1920×1080 FBO (measurement stays uncapped
//   and resolution-independent) and blitted here at an adaptive stride.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GpuStage(
    state: StressState,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceGone: () -> Unit,
    onStop: () -> Unit
) {
    val accent = Forge.phaseCalibration
    val fps = state.gpuFps.coerceAtLeast(0.0)
    val capPct = if (state.gpuBaselineFps > 0)
        (fps / state.gpuBaselineFps * 100.0).toFloat() else 100f

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── live GL scene ──
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurfaceReady(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) = onSurfaceGone()
                    })
                }
            }
        )

        // ── vignette — readability frame over the scene ──
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(
                Forge.bg.copy(alpha = 0.85f),
                Color.Transparent, Color.Transparent,
                Forge.bg.copy(alpha = 0.9f)
            ), startY = 0f, endY = Float.POSITIVE_INFINITY)
        ))

        // ── HUD top bar ──
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text("GPU CHANNEL", style = TextStyle(
                    color = accent, fontSize = 9.sp, fontFamily = InstrumentMono,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                Spacer(Modifier.height(4.dp))
                Text(
                    state.gpuName.ifEmpty { "GPU" },
                    style = TextStyle(color = Forge.ink1, fontSize = 11.sp, fontFamily = InstrumentMono)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    state.phase.displayName,
                    style = TextStyle(color = phaseColor(state.phase), fontSize = 9.sp,
                        fontFamily = InstrumentMono, letterSpacing = 1.5.sp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    TickerText(
                        value = fps.toFloat(),
                        format = { "%.0f".format(it) },
                        color = Ramp.forCapacity(capPct),
                        size = 42f
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("FPS", style = TextStyle(color = Forge.ink2, fontSize = 11.sp,
                        fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "DELIVERED %.0f%%".format(capPct),
                    style = TextStyle(color = Ramp.forCapacity(capPct), fontSize = 10.sp,
                        fontFamily = InstrumentMono, letterSpacing = 1.sp)
                )
                if (state.gpuBaselineFps > 0) {
                    Text(
                        "BASELINE %.0f FPS".format(state.gpuBaselineFps),
                        style = TextStyle(color = Forge.ink2, fontSize = 9.sp,
                            fontFamily = InstrumentMono)
                    )
                }
            }
        }

        // ── HUD bottom bar — hold-to-abort ──
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "RENDERING 1920×1080 OFFSCREEN · PRESENTED LIVE",
                style = TextStyle(color = Forge.ink2, fontSize = 8.sp,
                    fontFamily = InstrumentMono, letterSpacing = 1.5.sp)
            )
            Spacer(Modifier.height(10.dp))
            StageHoldAbort(onStop)
        }
    }
}

@Composable
private fun StageHoldAbort(onStop: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val holdProgress = remember { Animatable(0f) }
    var holdFired by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed && !holdFired) {
            holdProgress.animateTo(1f, animationSpec = tween(600, easing = LinearEasing))
            if (!holdFired) { holdFired = true; onStop() }
        } else if (!pressed) {
            holdProgress.snapTo(0f)
            if (holdFired) holdFired = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.6f)
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised.copy(alpha = 0.8f))
            .border(1.dp, Ramp.at(0.85f).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null) {}
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(16.dp)) {
                    drawCircle(Ramp.at(0.85f).copy(alpha = 0.25f), style = Stroke(2.5f))
                    drawArc(Ramp.at(0.85f), -90f, 360f * holdProgress.value, false,
                        style = Stroke(2.5f, cap = StrokeCap.Round))
                }
            }
            Text(
                if (pressed && !holdFired) "HOLDING…" else "HOLD TO ABORT",
                style = TextStyle(color = Ramp.at(0.85f), fontSize = 11.sp,
                    fontFamily = InstrumentMono, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            )
        }
    }
}
