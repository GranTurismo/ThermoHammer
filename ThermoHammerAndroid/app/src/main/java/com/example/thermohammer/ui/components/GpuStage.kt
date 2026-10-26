package com.example.thermohammer.ui.components

import android.text.format.DateFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.BenchmarkTest
import com.example.thermohammer.engine.GpuBench
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.MemTest
import com.example.thermohammer.engine.RunKind
import com.example.thermohammer.engine.RunPhase
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.engine.UxTest
import com.example.thermohammer.ui.theme.*
import java.util.Date
import kotlin.math.ceil

// ─────────────────────────────────────────────────────────────────────────────
// GPU STAGE — full-screen live render while the 3D benchmark runs.
//
//   The SurfaceView attaches directly to GpuBench's GL thread: the scene is
//   always rendered into the fixed 1920×1080 FBO (measurement stays uncapped
//   and resolution-independent) and blitted here at an adaptive stride. The
//   stage turns the phone to landscape and hides the system bars so the frame
//   fills the screen; both are restored when the run ends.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GpuStage(
    state: StressState,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceGone: () -> Unit,
    onStop: () -> Unit,
    onWebProgress: (String, Double, Int, Double, Int) -> Unit = { _, _, _, _, _ -> },
    onWebDone: (String) -> Unit = {},
    onWebFailed: (String) -> Unit = {}
) {
    val bench = state.runKind == RunKind.BENCHMARK
    val test = state.benchTest
    val ux = if (bench) state.uxTest else null
    val cpu = if (bench) state.cpuTest else null
    val ai = if (bench) state.aiTest else null
    val mem = if (bench) state.memTest else null
    val llm = if (bench) state.llmAccel else null
    val accent = if (llm != null) llmColor() else if (ai != null) aiColor() else if (cpu != null) cpuColor() else if (mem != null) memColor() else if (ux != null) uxColor(ux)
        else if (bench) benchmarkColor(test) else Forge.phaseCalibration
    val fps = state.gpuFps.coerceAtLeast(0.0)
    val capPct = if (state.gpuBaselineFps > 0)
        (fps / state.gpuBaselineFps * 100.0).toFloat() else 100f

    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (previous != null) activity.requestedOrientation = previous
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── live scene: GPU render, the web page, or the QR frame under the scanner ──
        when {
            llm != null -> LlmBenchView(state)
            ai != null -> AiBenchView(state.aiVisual, accent)
            cpu != null -> CpuBenchView(state)
            mem != null -> MemBenchView(state)
            ux?.page != null -> key(ux) { WebBenchView(ux.page, onWebProgress, onWebDone, onWebFailed) }
            ux == UxTest.QR -> QrBenchView(state.qrFrames, state.qrFrameIndex, state.qrFound, accent)
            ux == UxTest.PHOTO -> GpuSurface(onSurfaceReady, onSurfaceGone) { PhotoPanelLabels(accent) }
            else -> GpuSurface(onSurfaceReady, onSurfaceGone)
        }

        // ── vignette — thin readability bands top and bottom, the scene stays clear ──
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Forge.bg.copy(alpha = 0.7f), 0.22f to Color.Transparent,
                0.72f to Color.Transparent, 1f to Forge.bg.copy(alpha = 0.8f)
            )
        ))

        // ── UX 5: the player's bar, above the run progress · UX 6: the export dialog over the dimmed render ──
        if (ux == UxTest.VIDEO) state.videoOsd?.let { osd ->
            VideoOsdBar(osd, accent, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().displayCutoutPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 78.dp))
        }
        if (ux == UxTest.EXPORT) state.exportStage?.let { st ->
            Box(Modifier.fillMaxSize().background(Forge.bg.copy(alpha = 0.55f)))
            ExportDialog(st, accent, Modifier.align(Alignment.Center).statusBarsPadding().navigationBarsPadding()
                .displayCutoutPadding().padding(horizontal = 24.dp, vertical = 64.dp))
        }

        // ── HUD top bar ──
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().displayCutoutPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(if (llm != null) "LLM BENCHMARK" else if (ai != null) "AI BENCHMARK" else if (cpu != null) "CPU BENCHMARK" else if (mem != null) "MEM BENCHMARK"
                    else if (ux != null) "UX BENCHMARK"
                    else if (bench) "GPU BENCHMARK" else "GPU CHANNEL", style = TextStyle(
                    color = accent, fontSize = 9.sp, fontFamily = InstrumentMono,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                Spacer(Modifier.height(4.dp))
                if (llm != null) {
                    Text(
                        "LLM TEST ${llm.ordinal + 1}/${LlmAccel.entries.size} · ${llm.label} · LLAMA 3.2 3B",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.llmDetail, style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                        maxLines = 1)
                } else if (ai != null) {
                    Text(
                        "AI TEST ${ai.number}/${AiTest.entries.size} · ${ai.title}",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.aiDetail, style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                        maxLines = 1)
                } else if (cpu != null) {
                    Text(
                        "CPU TEST ${cpu.number}/${com.example.thermohammer.engine.cpu.CpuTest.entries.size} · ${cpu.title}",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.cpuKernel, style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                        maxLines = 1)
                } else if (mem != null) {
                    Text(
                        "MEM TEST ${mem.number}/${MemTest.entries.size} · ${mem.title}",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.memMeasure, style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                        maxLines = 1)
                } else if (ux != null) {
                    Text(
                        "UX TEST ${ux.number}/${UxTest.entries.size} · ${ux.title}",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.uxDetail, style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                        maxLines = 1)
                } else if (bench && test != null) {
                    Text(
                        "TEST ${test.number}/${BenchmarkTest.entries.size} · ${test.title}" +
                            if (test.rayTracing) " · VULKAN RAY TRACING" else "",
                        style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if ((ux == null || ux == UxTest.PHOTO) && cpu == null && ai == null && mem == null && llm == null) Text(
                    "${state.gpuName.ifEmpty { "GPU" }} · 1920×1080 offscreen",
                    style = TextStyle(color = Forge.ink1, fontSize = 10.sp, fontFamily = InstrumentMono),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (!bench) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        state.phase.displayName,
                        style = TextStyle(color = phaseColor(state.phase), fontSize = 9.sp,
                            fontFamily = InstrumentMono, letterSpacing = 1.5.sp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val shown = if (llm != null) state.llmRate.coerceAtLeast(0.0) else if (ai != null) state.aiRate.coerceAtLeast(0.0) else if (cpu != null) state.cpuRate.coerceAtLeast(0.0)
                        else if (mem != null) state.memRate.coerceAtLeast(0.0)
                        else if (ux != null) state.uxValue.coerceAtLeast(0.0) else fps
                    TickerText(
                        value = shown.toFloat(),
                        format = {
                            val fine = ux == UxTest.QR || ((cpu != null || mem != null || ai != null || llm != null || ux == UxTest.GALLERY || ux == UxTest.PHOTO) && it < 100f)
                            if (fine) "%.1f".format(it) else "%,.0f".format(it)
                        },
                        color = if (bench) Forge.ink0 else Ramp.forCapacity(capPct),
                        size = 36f
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (llm != null) "TOK/S" else if (ai != null) "INF/S" else if (cpu != null) "OPS/S" else if (mem != null) state.memUnit else if (ux != null) state.uxUnit else "FPS", style = TextStyle(color = Forge.ink2, fontSize = 11.sp,
                        fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                }
                if (!bench) Spacer(Modifier.height(2.dp))
                if (!bench) Text(
                    "DELIVERED %.0f%%".format(capPct),
                    style = TextStyle(color = Ramp.forCapacity(capPct), fontSize = 10.sp,
                        fontFamily = InstrumentMono, letterSpacing = 1.sp)
                )
                if (!bench && state.gpuBaselineFps > 0) {
                    Text(
                        "BASELINE %.0f FPS".format(state.gpuBaselineFps),
                        style = TextStyle(color = Forge.ink2, fontSize = 9.sp,
                            fontFamily = InstrumentMono)
                    )
                }
            }
        }

        // ── HUD bottom bar — run progress beside hold-to-abort ──
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .navigationBarsPadding().displayCutoutPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) { if (bench) BenchProgress(state) else RunProgress(state) }
            Spacer(Modifier.width(20.dp))
            StageHoldAbort(onStop, Modifier.width(190.dp))
        }
    }
}

// ── Run progress — warm-up ▸ calibrate ▸ measured, with the expected finish ──
//   The stage is only up once the renderer runs (after cooldown), so the
//   timeline is fixed: WARMUP_MS + CALIBRATION_MS + the chosen duration.

@Composable
private fun RunProgress(state: StressState) {
    val warm = StressEngine.WARMUP_MS / 1000f
    val cal = StressEngine.CALIBRATION_MS / 1000f
    val measured = (state.testDuration.seconds ?: 300).toFloat()
    val total = warm + cal + measured
    val elapsed = when (state.phase) {
        RunPhase.WARMUP, RunPhase.CALIBRATION ->            // one pre-run sample per SAMPLE_MS
            (state.preRunIps.size * StressEngine.SAMPLE_MS / 1000f).coerceAtMost(warm + cal)
        RunPhase.MEASURED -> warm + cal + state.elapsedSeconds
        else -> if (state.wasCompleted) total else 0f
    }.coerceIn(0f, total)
    // samples land every 0.25–1 s — glide between them so the bar never jumps
    val shown by animateFloatAsState(elapsed, tween(1000, easing = LinearEasing), label = "runProgress")

    val remaining = ceil(total - elapsed).toInt().coerceAtLeast(0)
    val context = LocalContext.current
    val endsAt = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + remaining * 1000L))
    val step = when (state.phase) {
        RunPhase.WARMUP -> 1
        RunPhase.CALIBRATION -> 2
        else -> 3
    }
    val label = TextStyle(fontSize = 9.sp, fontFamily = InstrumentMono, letterSpacing = 1.5.sp)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("${state.phase.displayName} · $step/3",
                style = label.copy(color = phaseColor(state.phase), fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text("%d%%".format((shown / total * 100).toInt()), style = label.copy(color = Forge.ink1))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val gap = 3.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            var x = 0f
            var segStart = 0f
            for ((len, color) in listOf(warm to Forge.phaseWarmup, cal to Forge.phaseCalibration,
                    measured to Forge.phaseMeasured)) {
                val w = (size.width - 2 * gap) * len / total
                drawRoundRect(color.copy(alpha = 0.2f), Offset(x, 0f), Size(w, size.height), radius)
                val fill = ((shown - segStart) / len).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(color, Offset(x, 0f), Size(w * fill, size.height), radius)
                x += w + gap
                segStart += len
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("ENDS IN %02d:%02d".format(remaining / 60, remaining % 60),
                style = label.copy(color = Forge.ink0, fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text("≈ $endsAt", style = label.copy(color = Forge.ink1))
        }
    }
}

// ── Benchmark progress — one segment per test (GPU, then UX), sized by its length ─

@Composable
private fun BenchProgress(state: StressState) {
    val gpu = state.benchPlan.ifEmpty { BenchmarkTest.entries }
    // (seconds, colour) per segment
    val cpuTests = com.example.thermohammer.engine.cpu.CpuTest.entries
    val segments = cpuTests.map { it.seconds to cpuColor() } + MemTest.entries.map { it.seconds to memColor() } +
        gpu.map { it.seconds to benchmarkColor(it) } +
        UxTest.entries.map { it.seconds to uxColor(it) } + AiTest.entries.map { it.seconds to aiColor() } +
        LlmAccel.entries.map { it.seconds to llmColor() }
    val total = segments.sumOf { it.first }.toFloat()
    val cpuTotal = cpuTests.sumOf { it.seconds }.toFloat() + MemTest.entries.sumOf { it.seconds }   // CPU, then MEM
    val gpuTotal = gpu.sumOf { it.seconds }.toFloat()
    val current = state.benchTest
    val ux = state.uxTest
    val cpu = state.cpuTest
    val ai = state.aiTest
    val mem = state.memTest
    val llm = state.llmAccel
    val uxTotal = UxTest.entries.sumOf { it.seconds }.toFloat()
    val aiTotal = AiTest.entries.sumOf { it.seconds }.toFloat()
    val elapsed = when {
        llm != null -> cpuTotal + gpuTotal + uxTotal + aiTotal + LlmAccel.entries.filter { it < llm }.sumOf { it.seconds } +
            state.llmProgress * llm.seconds
        ai != null -> cpuTotal + gpuTotal + uxTotal + AiTest.entries.filter { it.number < ai.number }.sumOf { it.seconds } +
            state.aiProgress * ai.seconds
        cpu != null -> cpuTests.filter { it.number < cpu.number }.sumOf { it.seconds } + state.cpuProgress * cpu.seconds
        mem != null -> cpuTests.sumOf { it.seconds } + MemTest.entries.filter { it.number < mem.number }.sumOf { it.seconds } +
            state.memProgress * mem.seconds
        ux != null -> cpuTotal + gpuTotal + UxTest.entries.filter { it.number < ux.number }.sumOf { it.seconds } +
            state.uxProgress * ux.seconds
        current != null -> cpuTotal + gpu.filter { it.number < current.number }.sumOf { it.seconds }.toFloat() +
            state.benchProgress * current.seconds
        else -> 0f
    }
    val shown by animateFloatAsState(elapsed, tween(300, easing = LinearEasing), label = "benchProgress")
    val remaining = ceil(total - elapsed).toInt().coerceAtLeast(0)
    val label = TextStyle(fontSize = 9.sp, fontFamily = InstrumentMono, letterSpacing = 1.5.sp)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(when {
                    llm != null -> "LLM TEST ${llm.ordinal + 1} · ${llm.label}"
                    ai != null -> "AI TEST ${ai.number} · ${ai.title}"
                    cpu != null -> "CPU TEST ${cpu.number} · ${cpu.title}"
                    mem != null -> "MEM TEST ${mem.number} · ${mem.title}"
                    ux != null -> "UX TEST ${ux.number} · ${ux.title}"
                    current != null -> "GPU TEST ${current.number} · ${current.title}"
                    else -> "PREPARING"
                },
                style = label.copy(color = Forge.ink0, fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text("%d%%".format((shown / total * 100).toInt()), style = label.copy(color = Forge.ink1))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val gap = 3.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            var x = 0f
            var segStart = 0f
            for ((secs, color) in segments) {
                val w = (size.width - gap * (segments.size - 1)) * secs / total
                drawRoundRect(color.copy(alpha = 0.2f), Offset(x, 0f), Size(w, size.height), radius)
                val fill = ((shown - segStart) / secs).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(color, Offset(x, 0f), Size(w * fill, size.height), radius)
                x += w + gap
                segStart += secs
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("ENDS IN %02d:%02d".format(remaining / 60, remaining % 60),
            style = label.copy(color = Forge.ink0, fontWeight = FontWeight.Bold))
    }
}

/**
 * The GPU tests' display. The surface buffer is exactly the render size, so presenting is a 1:1 copy
 * that costs the same on every phone; the compositor scales it. The view is 16:9 — it covers a
 * landscape screen (cropping a little top and bottom) and fits a portrait one.
 */
@Composable
private fun GpuSurface(onSurfaceReady: (Surface) -> Unit, onSurfaceGone: () -> Unit,
                       overlay: @Composable BoxScope.() -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = GpuBench.RENDER_W.toFloat() / GpuBench.RENDER_H
        val cover = maxWidth > maxHeight
        val wide = maxWidth / maxHeight > aspect
        val w = if (wide == cover) maxWidth else maxHeight * aspect
        val h = if (wide == cover) maxWidth / aspect else maxHeight
        Box(Modifier.requiredSize(w, h)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
                    holder.setFixedSize(GpuBench.RENDER_W, GpuBench.RENDER_H)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurfaceReady(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) = onSurfaceGone()
                    })
                }
            }
        )
        overlay()
        }
    }
}

/**
 * Names the four panels PhotoEditScene draws: each 4:3 photo sits in a quadrant of the 16:9 frame.
 * The labels hug the middle line, clear of the HUD bars at the top and bottom.
 */
@Composable
private fun BoxScope.PhotoPanelLabels(accent: Color) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = maxWidth * ((960f - com.example.thermohammer.engine.gpu.PhotoEditScene.PANEL_W) / 2f / 1920f)
        val photoH = maxHeight * (com.example.thermohammer.engine.gpu.PhotoEditScene.PANEL_H / 1080f)
        val gap = (maxHeight / 2 - photoH) / 2
        com.example.thermohammer.engine.gpu.PhotoEditScene.PANELS.forEachIndexed { i, label ->
            val col = i % 2; val top = i < 2
            Box(
                Modifier
                    .offset(x = maxWidth / 2 * col + inset + 8.dp,
                        y = if (top) maxHeight / 2 - gap - 30.dp else maxHeight / 2 + gap + 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Forge.bg.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("${i + 1} · $label", style = TextStyle(color = accent, fontSize = 10.sp, fontFamily = InstrumentMono,
                    fontWeight = FontWeight.Black, letterSpacing = 1.sp))
            }
        }
    }
}

/** A player's chrome over the decoded clip — timecode, scrub bar, format, bitrate and how far past real time it runs. */
@Composable
private fun VideoOsdBar(osd: com.example.thermohammer.engine.VideoOsd, accent: Color, modifier: Modifier = Modifier) {
    fun tc(s: Double) = "%02d:%05.2f".format((s / 60).toInt(), s % 60)
    val chip = TextStyle(color = Forge.ink0, fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Forge.bg.copy(alpha = 0.62f))
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▶", style = chip.copy(color = accent))
            Spacer(Modifier.width(8.dp))
            Text("${tc(osd.positionS)} / ${tc(osd.durationS)}", style = chip)
            Spacer(Modifier.weight(1f))
            Text(osd.format, style = chip.copy(color = Forge.ink1), modifier = Modifier.padding(end = 10.dp))
            Text("${osd.mbps} MBPS", style = chip.copy(color = Forge.ink1), modifier = Modifier.padding(end = 10.dp))
            Text(if (osd.speed > 0) "×%.1f REAL TIME".format(osd.speed) else "×— REAL TIME",
                style = chip.copy(color = Forge.bg),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(accent).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val y = size.height / 2
            val track = 3.dp.toPx()
            drawRoundRect(Forge.ink2.copy(alpha = 0.35f), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track / 2))
            val f = if (osd.durationS > 0) (osd.positionS / osd.durationS).coerceIn(0.0, 1.0).toFloat() else 0f
            drawRoundRect(accent, Offset(0f, y - track / 2), Size(size.width * f, track), CornerRadius(track / 2))
            drawCircle(Forge.ink0, 5.dp.toPx(), Offset(size.width * f, y))
        }
    }
}

/**
 * An editor's export dialog: the file, its format and encoder, a progress bar of the frames written (the job is a
 * fixed timeline, so this is the export's real progress), time spent and left, the step, and the render queue.
 */
@Composable
internal fun ExportDialog(st: com.example.thermohammer.engine.ExportStage, accent: Color, modifier: Modifier = Modifier) {
    val j = st.job
    val label = TextStyle(color = Forge.ink2, fontSize = 8.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    val body = TextStyle(color = Forge.ink1, fontSize = 9.sp, fontFamily = InstrumentMono)
    val shown by animateFloatAsState(st.progress, tween(220), label = "export")
    val active = st.queue.indexOfFirst { it.job == j }
    Column(modifier.widthIn(max = 600.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Forge.surface.copy(alpha = 0.94f))
        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⤓ EXPORTING  ${active + 1} OF ${st.queue.size}", style = label.copy(color = accent))
            Spacer(Modifier.weight(1f))
            Text(st.speedText,
                style = label.copy(color = Forge.bg),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(accent).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(j.fileName, style = TextStyle(color = Forge.ink0, fontSize = 13.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black),
            maxLines = 1)
        Text("${j.label} · ${j.width}×${j.height} · ${j.fps} FPS · ${j.mbps} MBPS · ${if (st.hardware) "HARDWARE" else "SOFTWARE"} ${st.encoder ?: ""}",
            style = body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(12.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Forge.ink2.copy(alpha = 0.3f), cornerRadius = r)
                drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent)), size = Size(size.width * shown, size.height), cornerRadius = r)
            }
            Spacer(Modifier.width(10.dp))
            Text("%d%%".format((st.progress * 100).toInt()), style = TextStyle(color = Forge.ink0, fontSize = 16.sp,
                fontFamily = InstrumentMono, fontWeight = FontWeight.Black), modifier = Modifier.widthIn(min = 48.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("${st.framesDone} / ${j.frames} FRAMES · %.1f S · ".format(st.elapsedS) + st.timeLeftText + st.step,
            style = body.copy(color = if (st.step == "ENCODING" || st.step.startsWith("FINISHING") || st.step.startsWith("CHECKING")) Forge.ink1 else Ramp.at(0.85f)),
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.hairline))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            for (item in st.queue) {
                val (mark, color) = when (item.state) {
                    com.example.thermohammer.engine.ExportStage.State.DONE -> "✓" to accent
                    com.example.thermohammer.engine.ExportStage.State.ACTIVE -> "●" to Forge.ink0
                    com.example.thermohammer.engine.ExportStage.State.SKIPPED -> "✗" to Ramp.at(0.85f)
                    com.example.thermohammer.engine.ExportStage.State.PENDING -> "○" to Forge.ink2
                }
                Column(Modifier.weight(1f)) {
                    Text("$mark ${item.job.res} ${item.job.label}", style = label.copy(color = color), maxLines = 1)
                    Text(when (item.state) {
                        com.example.thermohammer.engine.ExportStage.State.DONE -> "%.0f FPS · %.1f dB".format(item.fps, item.psnr)
                        com.example.thermohammer.engine.ExportStage.State.ACTIVE -> if (st.fps > 0) "%.0f FPS".format(st.fps) else "…"
                        com.example.thermohammer.engine.ExportStage.State.SKIPPED -> "0 POINTS"
                        com.example.thermohammer.engine.ExportStage.State.PENDING -> "QUEUED"
                    }, style = body.copy(fontSize = 8.sp, color = Forge.ink2), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StageHoldAbort(onStop: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier
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
