package com.example.thermohammer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// WELCOME — what ThermoHammer is and why its numbers can be trusted, on six
// pages: the instrument, its six sections, the same work on every platform,
// scoring that can't be gamed, Phone Health and the heat test. Every picture
// is drawn live (one frame clock, read only while drawing, so the pages
// redraw without recomposing); each page plays its entrance as it slides in.
// Shown on first launch; the "?" in the header opens it again. iOS repeats it
// (WelcomeView.swift).
// ─────────────────────────────────────────────────────────────────────────────

/** Opens the welcome again (the "?" in the header). */
val LocalShowIntro = staticCompositionLocalOf<() -> Unit> { {} }

private data class Page(val kicker: String, val title: String, val body: String, val accent: Color,
                        val art: @Composable (time: State<Float>, entrance: State<Float>) -> Unit)

@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = remember {
        listOf(
            Page("THERMOHAMMER", "The benchmark that tells the truth",
                "Six sections, scored on our server, the same test on every phone — and the only one that also tells you " +
                    "when your phone gets too hot to keep up.", Forge.phaseMeasured) { t, a -> ReactorArt(t, a) },
            Page("01 · SIX SECTIONS", "One honest score",
                "Processor, memory and storage, 3D graphics with ray tracing, everyday apps, AI on the NPU, and a language " +
                    "model running right on your phone. Each test is 6,000 points on a Snapdragon 8 Elite.",
                Ramp.at(0.25f)) { t, a -> SectionsArt(t, a) },
            Page("02 · SAME WORK EVERYWHERE", "Byte-identical on every platform",
                "Android, iPhone and Mac run the very same C++, the same 3D scenes, the same AI models and the same seeded " +
                    "language model. A score means the same thing on every device.", Forge.phaseCooldown) { t, a -> SameWorkArt(t, a) },
            Page("03 · CAN'T BE GAMED", "Scored where no one can tune for it",
                "Phone makers have been caught boosting known benchmarks. Your raw results are signed and scored on our " +
                    "server, and only clean runs — cool, unplugged, nothing else running — reach the leaderboard.",
                Forge.phaseCalibration) { t, a -> ServerArt(t, a) },
            Page("04 · PHONE HEALTH", "Is your phone as fast as it should be?",
                "Every run is compared with other units of your model and with your own past runs. If it's slower, you " +
                    "find out why: heat, background apps, full storage or an update.", Ramp.at(0.5f)) { t, a -> HealthArt(t, a) },
            Page("05 · THE HEAT TEST", "See when it starts to throttle",
                "The stress test hammers the chip for 5 to 30 minutes and charts every second: when it slows down, how " +
                    "far, and what caused it.", Ramp.at(0.68f)) { t, a -> HeatArt(t, a) },
        )
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val time = rememberFrameTime()
    val last = pager.currentPage == pages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(Modifier.fillMaxSize().background(Forge.bg).clickable(remember { MutableInteractionSource() }, null) {}) {
        // the page's accent washes the background, blended while swiping (read while drawing: a swipe only redraws)
        Canvas(Modifier.fillMaxSize()) {
            val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, pages.lastIndex.toFloat())
            val from = pages[pos.toInt()].accent
            val to = pages[(pos.toInt() + 1).coerceAtMost(pages.lastIndex)].accent
            val wash = lerpColor(from, to, pos - pos.toInt())
            drawRect(Brush.radialGradient(listOf(wash.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.32f), radius = size.width * 0.95f))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⌖ THERMOHAMMER", style = ThermoType.label(10f, Forge.ink1))
                Spacer(Modifier.weight(1f))
                if (!last) Text("SKIP", style = ThermoType.label(10f, Forge.ink1),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(8.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                PageView(pages[i], i, pager, time)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Dots(pages.size, pager, pages.map { it.accent })
                Spacer(Modifier.weight(1f))
                val accent = pages[pager.currentPage].accent
                val w by animateDpAsState(if (last) 168.dp else 116.dp, spring(dampingRatio = 0.8f, stiffness = 300f), label = "cta")
                Box(
                    Modifier.width(w).height(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(accent, lerpColor(accent, Forge.ink0, 0.25f))))
                        .clickable { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (last) "GET STARTED" else "NEXT  →", style = ThermoType.title(12f, Forge.bg))
                }
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

/** Seconds since the screen opened, advanced every frame — read it only while drawing. */
@Composable
private fun rememberFrameTime(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

@Composable
private fun PageView(page: Page, index: Int, pager: PagerState, time: State<Float>) {
    // the entrance plays as the page slides into view, and again on the next visit (the pager composes a page ahead
    // of time, out of view, so its being composed doesn't mean it's seen)
    val offset = { (pager.currentPage - index) + pager.currentPageOffsetFraction }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { abs(offset()) < 1f }.collectLatest { inView ->
            if (inView) appear.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) else appear.snapTo(0f)
        }
    }
    // the picture lags its page by less than the page's margin and is gone by the time it gets there: nothing of the
    // old page shows over the new one, and no edge has to cut it off
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier.fillMaxWidth().weight(1f).graphicsLayer {
                val o = abs(offset()).coerceAtMost(1f)
                translationX = offset() * 40.dp.toPx()              // a little behind the swipe: depth
                alpha = 1f - o
                scaleX = 1f - o * 0.1f; scaleY = scaleX
            },
            contentAlignment = Alignment.Center
        ) { page.art(time, appear.asState()) }
        Column(Modifier.graphicsLayer {
            val o = offset()
            translationX = o * 24.dp.toPx()
            alpha = 1f - abs(o).coerceAtMost(1f)
        }) {
            Text(page.kicker, style = ThermoType.label(10f, page.accent))
            Spacer(Modifier.height(8.dp))
            Text(page.title, style = TextStyle(color = Forge.ink0, fontSize = 27.sp, lineHeight = 32.sp, fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(10.dp))
            Text(page.body, style = TextStyle(color = Forge.ink1, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = InstrumentMono))
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Drawn rather than laid out, so a swipe only redraws them. Their widths always add up to the same. */
@Composable
private fun Dots(count: Int, pager: PagerState, accents: List<Color>) {
    Canvas(Modifier.width((6 * count + 18 + 6 * (count - 1)).dp).height(6.dp)) {
        val pos = pager.currentPage + pager.currentPageOffsetFraction
        val dot = size.height
        var x = 0f
        for (i in 0 until count) {
            val near = (1f - abs(pos - i)).coerceIn(0f, 1f)
            val w = dot + 18.dp.toPx() * near
            drawRoundRect(lerpColor(Forge.ink2.copy(alpha = 0.5f), accents[i], near), Offset(x, 0f), Size(w, dot), CornerRadius(dot / 2))
            x += w + dot
        }
    }
}

// ── the pictures ─────────────────────────────────────────────────────────────

private val ArtModifier = Modifier.fillMaxWidth().aspectRatio(1.1f)

private fun ease(x: Float) = x.coerceIn(0f, 1f).let { 1 - (1 - it) * (1 - it) * (1 - it) }

/** Progress of a staggered piece: starts at [from] of the entrance, takes [span]. */
private fun stage(appear: Float, from: Float, span: Float = 0.35f) = ease((appear - from) / span)

/** The instrument: rings of the thermal ramp turning around a glowing core, a strike every few seconds. */
@Composable
private fun ReactorArt(time: State<Float>, entrance: State<Float>) {
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val c = center
        val r = size.minDimension * 0.36f
        val a = ease(appear)
        // core glow, breathing
        val breath = 0.85f + 0.15f * sin(t * 1.6f)
        drawCircle(Brush.radialGradient(listOf(Ramp.at(0.55f).copy(alpha = 0.55f * a), Ramp.at(0.7f).copy(alpha = 0.18f * a),
            Color.Transparent), c, r * 0.95f * breath), r * 0.95f * breath, c)
        drawCircle(Ramp.at(0.9f).copy(alpha = 0.9f * a), r * 0.11f * breath, c)
        // three rings, each turning its own way, the ramp sweeping round them
        for (k in 0 until 3) {
            val rr = r * (0.48f + 0.26f * k) * (0.7f + 0.3f * a)
            val dir = if (k % 2 == 0) 1 else -1
            rotate(dir * t * (24f + 14f * k) + k * 40f, c) {
                drawArc(Brush.sweepGradient(listOf(Ramp.at(0f), Ramp.at(0.25f), Ramp.at(0.5f), Ramp.at(0.85f), Ramp.at(0f)), c),
                    0f, 300f * a, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                    style = Stroke((5 - k).dp.toPx(), cap = StrokeCap.Round))
                for (j in 0 until 12) {                                   // instrument ticks
                    val ang = j * 30f * PI.toFloat() / 180f
                    val p0 = Offset(c.x + cos(ang) * (rr + 6.dp.toPx()), c.y + sin(ang) * (rr + 6.dp.toPx()))
                    val p1 = Offset(c.x + cos(ang) * (rr + 11.dp.toPx()), c.y + sin(ang) * (rr + 11.dp.toPx()))
                    drawLine(Forge.ink2.copy(alpha = 0.5f * a), p0, p1, 1.dp.toPx())
                }
            }
        }
        // the hammer: a shockwave every 2.6 s
        val w = (t % 2.6f) / 2.6f
        drawCircle(Ramp.at(0.6f).copy(alpha = (1 - w) * 0.45f * a), r * (0.2f + 1.1f * w), c, style = Stroke((3 * (1 - w) + 0.5f).dp.toPx()))
        // sparks rising off the heat
        for (i in 0 until 26) {
            val seed = i * 37.17f
            val life = ((t * (0.22f + (i % 5) * 0.05f) + seed) % 1f)
            val x = c.x + sin(seed) * r * 1.1f + sin(t + seed) * 8.dp.toPx()
            val y = c.y + r * 1.0f - life * r * 2.2f
            drawCircle(Ramp.at(0.4f + 0.5f * life).copy(alpha = (1 - life) * 0.8f * a), (1.4f + (i % 3)).dp.toPx() * (1 - life), Offset(x, y))
        }
    }
}

/** Six tiles light up one after another, each running its own little workload, the total counting up. */
@Composable
private fun SectionsArt(time: State<Float>, entrance: State<Float>) {
    val tm = rememberTextMeasurer()
    val names = listOf("CPU", "MEM", "GPU", "UX", "AI", "LLM")
    val colors = listOf(cpuColor(), memColor(), Forge.phaseCalibration, Forge.phaseMeasured, aiColor(), llmColor())
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val gap = 10.dp.toPx()
        val tileW = (size.width - gap * 2) / 3
        val tileH = (size.height * 0.72f - gap) / 2
        for (i in 0 until 6) {
            val p = stage(appear, i * 0.09f, 0.4f)
            val col = i % 3; val row = i / 3
            val x = col * (tileW + gap); val y = row * (tileH + gap) + (1 - p) * 30.dp.toPx()
            val color = colors[i]
            drawRoundRect(Forge.surface.copy(alpha = p), Offset(x, y), Size(tileW, tileH), CornerRadius(14.dp.toPx()))
            drawRoundRect(color.copy(alpha = 0.55f * p), Offset(x, y), Size(tileW, tileH), CornerRadius(14.dp.toPx()),
                style = Stroke(1.dp.toPx()))
            drawLabel(tm, names[i], Offset(x + 12.dp.toPx(), y + 10.dp.toPx()), color.copy(alpha = p), 11f)
            // its workload: bars that never stop
            val bars = 7
            val bw = (tileW - 24.dp.toPx()) / (bars * 1.6f)
            for (b in 0 until bars) {
                val h = (0.25f + 0.75f * (0.5f + 0.5f * sin(t * (2.2f + i * 0.3f) + b * 0.9f + i))) * tileH * 0.42f * p
                drawRoundRect(color.copy(alpha = 0.8f * p), Offset(x + 12.dp.toPx() + b * bw * 1.6f, y + tileH - 12.dp.toPx() - h),
                    Size(bw, h), CornerRadius(bw / 2))
            }
        }
        // the total
        val total = (36026 * stage(appear, 0.45f, 0.55f)).toInt()
        val totalY = size.height * 0.80f
        drawLabel(tm, "TOTAL", Offset(0f, totalY), Forge.ink2, 10f)
        drawLabel(tm, "%,d".format(total), Offset(0f, totalY + 14.dp.toPx()), Forge.ink0, 30f, bold = true)
        drawLabel(tm, "CPU + MEM + GPU + UX + AI + LLM", Offset(size.width * 0.42f, totalY + 26.dp.toPx()), Forge.ink2, 9f)
    }
}

/** The same packets, in the same order, flowing from one seed into an Android phone, an iPhone and a Mac. */
@Composable
private fun SameWorkArt(time: State<Float>, entrance: State<Float>) {
    val tm = rememberTextMeasurer()
    val packet = listOf(cpuColor(), memColor(), Forge.phaseCalibration, Forge.phaseMeasured, aiColor(), llmColor())
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val src = Offset(size.width / 2, size.height * 0.12f)
        val a = ease(appear)
        // the seed
        drawRoundRect(Forge.surface, Offset(src.x - 62.dp.toPx(), src.y - 16.dp.toPx()), Size(124.dp.toPx(), 32.dp.toPx()),
            CornerRadius(10.dp.toPx()))
        drawRoundRect(Forge.phaseCooldown.copy(alpha = 0.6f), Offset(src.x - 62.dp.toPx(), src.y - 16.dp.toPx()),
            Size(124.dp.toPx(), 32.dp.toPx()), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawLabelCentered(tm, "ONE TEST · ONE SEED", src, Forge.phaseCooldown, 9f)
        val devY = size.height * 0.62f
        val xs = listOf(size.width * 0.17f, size.width * 0.5f, size.width * 0.83f)
        val labels = listOf("ANDROID", "iPHONE", "MAC")
        for ((k, x) in xs.withIndex()) {
            val p = stage(appear, 0.1f + k * 0.12f, 0.45f)
            val top = Offset(x, devY - 58.dp.toPx())
            // the path, drawn in, then packets riding it in lockstep
            val path = Path().apply {
                moveTo(src.x, src.y + 16.dp.toPx())
                cubicTo(src.x, src.y + 70.dp.toPx(), x, top.y - 70.dp.toPx(), x, top.y)
            }
            drawPath(path, Forge.ink2.copy(alpha = 0.35f * p), style = Stroke(1.5f.dp.toPx()))
            val pm = android.graphics.PathMeasure(path.asAndroidPath(), false)
            val pos = FloatArray(2)
            for (j in 0 until 6) {
                val u = ((t * 0.32f + j / 6f) % 1f)
                pm.getPosTan(pm.length * u, pos, null)
                drawRoundRect(packet[j].copy(alpha = p * (0.4f + 0.6f * sin(u * PI.toFloat()))),
                    Offset(pos[0] - 4.dp.toPx(), pos[1] - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
            // the device
            drawDevice(k, Offset(x, devY), p)
            drawLabelCentered(tm, labels[k], Offset(x, devY + 70.dp.toPx()), Forge.ink1.copy(alpha = p), 9f)
            // the same fingerprint on every screen
            val shown = "26f8354e".take((8 * stage(appear, 0.55f + k * 0.05f, 0.35f)).toInt())     // all eight by the end
            drawLabelCentered(tm, shown, Offset(x, devY), Forge.phaseCooldown.copy(alpha = p), 9f)
        }
        drawLabelCentered(tm, "same code · same scenes · same models · same result", Offset(size.width / 2, size.height * 0.97f),
            Forge.ink2.copy(alpha = a), 9f)
    }
}

private fun DrawScope.drawDevice(kind: Int, c: Offset, p: Float) {
    val stroke = Stroke(1.5f.dp.toPx())
    val col = Forge.ink1.copy(alpha = 0.8f * p)
    when (kind) {
        0, 1 -> {                                                     // a phone: Android with a punch hole, iPhone with an island
            val w = 54.dp.toPx(); val h = 112.dp.toPx()
            val tl = Offset(c.x - w / 2, c.y - h / 2)
            drawRoundRect(Forge.surface.copy(alpha = p), tl, Size(w, h), CornerRadius(12.dp.toPx()))
            drawRoundRect(col, tl, Size(w, h), CornerRadius(12.dp.toPx()), style = stroke)
            if (kind == 0) drawCircle(col, 2.5f.dp.toPx(), Offset(c.x, tl.y + 8.dp.toPx()))
            else drawRoundRect(col, Offset(c.x - 9.dp.toPx(), tl.y + 6.dp.toPx()), Size(18.dp.toPx(), 5.dp.toPx()),
                CornerRadius(3.dp.toPx()))
        }
        else -> {                                                     // a laptop
            val w = 92.dp.toPx(); val h = 60.dp.toPx()
            val tl = Offset(c.x - w / 2, c.y - h / 2 - 6.dp.toPx())
            drawRoundRect(Forge.surface.copy(alpha = p), tl, Size(w, h), CornerRadius(6.dp.toPx()))
            drawRoundRect(col, tl, Size(w, h), CornerRadius(6.dp.toPx()), style = stroke)
            drawRoundRect(col, Offset(c.x - w / 2 - 10.dp.toPx(), tl.y + h + 2.dp.toPx()), Size(w + 20.dp.toPx(), 5.dp.toPx()),
                CornerRadius(3.dp.toPx()))
        }
    }
}

/** Raw results leave the phone signed, the server scores them, and only clean runs pass its checks. */
@Composable
private fun ServerArt(time: State<Float>, entrance: State<Float>) {
    val tm = rememberTextMeasurer()
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val a = ease(appear)
        val phone = Offset(size.width * 0.16f, size.height * 0.34f)
        val server = Offset(size.width * 0.84f, size.height * 0.34f)
        drawDevice(0, phone, a)
        // the server: a rack of three units, lights blinking
        for (u in 0 until 3) {
            val tl = Offset(server.x - 38.dp.toPx(), server.y - 46.dp.toPx() + u * 32.dp.toPx())
            drawRoundRect(Forge.surface.copy(alpha = a), tl, Size(76.dp.toPx(), 26.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawRoundRect(Forge.phaseCalibration.copy(alpha = 0.6f * a), tl, Size(76.dp.toPx(), 26.dp.toPx()),
                CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
            for (l in 0 until 3) {
                val on = sin(t * 5f + u * 1.7f + l * 2.3f) > 0.2f
                drawCircle((if (on) Forge.phaseMeasured else Forge.ink2).copy(alpha = a), 2.5f.dp.toPx(),
                    Offset(tl.x + 12.dp.toPx() + l * 9.dp.toPx(), tl.y + 13.dp.toPx()))
            }
        }
        // signed packets crossing over
        val y = phone.y
        drawLine(Forge.ink2.copy(alpha = 0.4f * a), Offset(phone.x + 36.dp.toPx(), y), Offset(server.x - 46.dp.toPx(), y),
            1.5f.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f), -t * 40f))
        for (j in 0 until 3) {
            val u = (t * 0.45f + j / 3f) % 1f
            val x = phone.x + 36.dp.toPx() + u * (server.x - phone.x - 82.dp.toPx())
            drawRoundRect(Forge.phaseCalibration.copy(alpha = a * sin(u * PI.toFloat())), Offset(x - 9.dp.toPx(), y - 6.dp.toPx()),
                Size(18.dp.toPx(), 12.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        // the lock in the middle
        val lc = Offset(size.width / 2, y - 22.dp.toPx())
        drawArc(Forge.phaseCalibration.copy(alpha = a), 180f, 180f, false, Offset(lc.x - 7.dp.toPx(), lc.y - 12.dp.toPx()),
            Size(14.dp.toPx(), 16.dp.toPx()), style = Stroke(2.dp.toPx()))
        drawRoundRect(Forge.phaseCalibration.copy(alpha = a), Offset(lc.x - 10.dp.toPx(), lc.y - 4.dp.toPx()),
            Size(20.dp.toPx(), 15.dp.toPx()), CornerRadius(3.dp.toPx()))
        drawLabelCentered(tm, "SIGNED · SCORED ON THE SERVER", Offset(size.width / 2, y + 22.dp.toPx()), Forge.ink2.copy(alpha = a), 9f)
        // the checks, ticking green one by one
        val checks = listOf("UNPLUGGED", "STARTED COOL", "NOTHING ELSE RUNNING", "NO POWER SAVING")
        for ((i, label) in checks.withIndex()) {
            val p = stage(appear, 0.3f + i * 0.13f, 0.3f)
            val cy = size.height * 0.62f + i * 26.dp.toPx()
            val x0 = size.width * 0.18f
            drawRoundRect(Forge.surface.copy(alpha = p), Offset(x0, cy - 10.dp.toPx()), Size(size.width * 0.64f, 21.dp.toPx()),
                CornerRadius(7.dp.toPx()))
            val ok = p >= 1f
            drawCircle((if (ok) Forge.phaseMeasured else Forge.ink2).copy(alpha = p), 6.dp.toPx(), Offset(x0 + 14.dp.toPx(), cy))
            if (ok) {
                val tick = Path().apply {
                    moveTo(x0 + 11.dp.toPx(), cy); lineTo(x0 + 13.5f.dp.toPx(), cy + 2.5f.dp.toPx()); lineTo(x0 + 17.5f.dp.toPx(), cy - 2.5f.dp.toPx())
                }
                drawPath(tick, Forge.bg, style = Stroke(1.6f.dp.toPx(), cap = StrokeCap.Round))
            }
            drawLabel(tm, label, Offset(x0 + 28.dp.toPx(), cy - 7.dp.toPx()), (if (ok) Forge.ink0 else Forge.ink2).copy(alpha = p), 10f)
        }
    }
}

/** Other units of the model pile up into a curve; this phone's marker slides in and the reason appears. */
@Composable
private fun HealthArt(time: State<Float>, entrance: State<Float>) {
    val tm = rememberTextMeasurer()
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val base = size.height * 0.62f
        val h = size.height * 0.42f
        fun curve(x: Float) = exp(-((x - 0.55f) * (x - 0.55f)) / (2 * 0.12f * 0.12f))
        val grow = stage(appear, 0f, 0.5f)
        // the model's runs as falling dots, settling into the curve
        for (i in 0 until 70) {
            val u = ((i * 0.6180339f) % 1f)
            val col = (curve(u) * 9).toInt()
            val stack = (i * 7) % (col + 1)
            val dy = base - 6.dp.toPx() - stack * 9.dp.toPx() * curve(u).coerceAtLeast(0.15f)
            val fall = stage(appear, 0.02f * (i % 25), 0.3f)
            drawCircle(Forge.ink1.copy(alpha = 0.35f * fall), 2.6f.dp.toPx(), Offset(u * size.width, dy - (1 - fall) * 120.dp.toPx()))
        }
        val path = Path().apply {
            moveTo(0f, base)
            for (k in 0..100) { val x = k / 100f; lineTo(x * size.width, base - curve(x) * h * grow) }
            lineTo(size.width, base); close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Ramp.at(0.25f).copy(alpha = 0.28f), Color.Transparent), base - h, base))
        // the middle half, the median
        val p25 = 0.47f; val p75 = 0.63f
        drawRect(Ramp.at(0.25f).copy(alpha = 0.12f * grow), Offset(p25 * size.width, base - h), Size((p75 - p25) * size.width, h))
        drawLine(Forge.ink0.copy(alpha = 0.6f * grow), Offset(0.55f * size.width, base - h * 1.05f), Offset(0.55f * size.width, base), 1.5f.dp.toPx())
        drawLabelCentered(tm, "TYPICAL", Offset(0.55f * size.width, base - h * 1.12f), Forge.ink1.copy(alpha = grow), 9f)
        drawLine(Forge.ink2, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        // you: sliding in from the left
        val you = 0.08f + (0.36f - 0.08f) * stage(appear, 0.45f, 0.4f) + 0.004f * sin(t * 2f)
        val ux = you * size.width
        val accent = Ramp.at(0.5f)
        drawLine(accent, Offset(ux, base - h * 0.6f), Offset(ux, base), 2.dp.toPx())
        drawCircle(accent, 6.dp.toPx(), Offset(ux, base - h * 0.6f))
        drawLabelCentered(tm, "YOU · 86%", Offset(ux, base - h * 0.6f - 16.dp.toPx()), accent, 10f, bold = true)
        // the reason
        val r = stage(appear, 0.8f, 0.25f)
        val boxY = base + 18.dp.toPx() + (1 - r) * 12.dp.toPx()
        drawRoundRect(Forge.surface.copy(alpha = r), Offset(0f, boxY), Size(size.width, 54.dp.toPx()), CornerRadius(10.dp.toPx()))
        drawRoundRect(accent.copy(alpha = 0.5f * r), Offset(0f, boxY), Size(size.width, 54.dp.toPx()), CornerRadius(10.dp.toPx()),
            style = Stroke(1.dp.toPx()))
        drawLabel(tm, "▲ GPU 18% BELOW OTHER UNITS", Offset(12.dp.toPx(), boxY + 9.dp.toPx()), Ramp.at(0.85f).copy(alpha = r), 10f, bold = true)
        drawLabel(tm, "It throttled. Let it cool and take the case off.", Offset(12.dp.toPx(), boxY + 28.dp.toPx()),
            Forge.ink1.copy(alpha = r), 10f)
    }
}

/** Temperature climbs; performance holds, then sags at the onset — drawn as it happens, over and over. */
@Composable
private fun HeatArt(time: State<Float>, entrance: State<Float>) {
    val tm = rememberTextMeasurer()
    Canvas(ArtModifier) {
        val appear = entrance.value
        val t = time.value
        val a = ease(appear)
        val left = 0f; val right = size.width
        val top = size.height * 0.1f; val bottom = size.height * 0.78f
        // grid
        for (g in 0..4) {
            val y = top + (bottom - top) * g / 4
            drawLine(Forge.hairline, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val sweep = ((t % 7f) / 6f).coerceAtMost(1f)                 // draws in over 6 s, holds a second
        val onset = 0.42f
        fun perf(x: Float) = if (x < onset) 0.92f - 0.02f * sin(x * 30) else 0.92f - 0.34f * (1 - exp(-(x - onset) * 6f)) - 0.02f * sin(x * 30)
        fun temp(x: Float) = 0.15f + 0.7f * (1 - exp(-x * 3.2f))
        val n = (120 * sweep * a).toInt().coerceAtLeast(1)
        val tp = Path(); val pp = Path()
        for (k in 0..n) {
            val x = k / 120f
            val px = left + x * (right - left)
            val ty = bottom - temp(x) * (bottom - top); val py = bottom - perf(x) * (bottom - top)
            if (k == 0) { tp.moveTo(px, ty); pp.moveTo(px, py) } else { tp.lineTo(px, ty); pp.lineTo(px, py) }
        }
        drawPath(tp, Brush.horizontalGradient(listOf(Ramp.at(0.2f), Ramp.at(0.55f), Ramp.at(0.85f))), style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
        drawPath(pp, Forge.phaseMeasured, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round))
        if (sweep > onset) {
            val ox = left + onset * (right - left)
            drawLine(Ramp.at(0.85f).copy(alpha = 0.8f), Offset(ox, top), Offset(ox, bottom), 1.5f.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            drawLabel(tm, "THROTTLING STARTS · 4:12", Offset(ox + 6.dp.toPx(), top + 2.dp.toPx()), Ramp.at(0.85f), 9f, bold = true)
        }
        drawLabel(tm, "PERFORMANCE", Offset(left, bottom + 12.dp.toPx()), Forge.phaseMeasured, 10f, bold = true)
        drawLabel(tm, "TEMPERATURE", Offset(size.width * 0.5f, bottom + 12.dp.toPx()), Ramp.at(0.6f), 10f, bold = true)
    }
}

// ── text on a canvas ─────────────────────────────────────────────────────────

private fun DrawScope.drawLabel(tm: TextMeasurer, text: String, at: Offset, color: Color, size: Float, bold: Boolean = false) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = size.sp, fontFamily = InstrumentMono,
        fontWeight = if (bold) FontWeight.Black else FontWeight.Bold, letterSpacing = (size * 0.06f).sp))
    drawText(layout, topLeft = at)
}

private fun DrawScope.drawLabelCentered(tm: TextMeasurer, text: String, at: Offset, color: Color, size: Float, bold: Boolean = false) {
    val style = TextStyle(color = color, fontSize = size.sp, fontFamily = InstrumentMono,
        fontWeight = if (bold) FontWeight.Black else FontWeight.Bold, letterSpacing = (size * 0.06f).sp, textAlign = TextAlign.Center)
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
