package com.example.thermohammer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.ai.AiVisual
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ui.theme.InstrumentMono
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// AI STAGE — what each model saw and what it made of it, while it is timed on
// the CPU, the GPU and the NPU: classes with confidence bars, detection boxes,
// the street's class map, the ×4 upscale beside its pixelated input, the depth
// map beside the photo, and what YAMNet heard in the spectrogram.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiBenchView(visual: AiVisual?, accent: Color) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 64.dp, bottom = 70.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.Center) {
        val v = visual ?: return@Box
        when (v.test) {
            AiTest.UPSCALE -> SideBySide(v, "INPUT · 128×128", "AI ×4 · 512×512", accent, pixelatedLeft = true)
            AiTest.DEPTH -> SideBySide(v, "PHOTO", "DEPTH · NEAR = BRIGHT", accent, pixelatedLeft = false)
            else -> Overlaid(v, accent)
        }
    }
}

/** The picture fitted into the stage, with boxes, a class map or labels drawn over it. */
@Composable
private fun Overlaid(v: AiVisual, accent: Color) {
    val picture = remember(v.picture) { v.picture.asImageBitmap() }
    val result = remember(v.result) { v.result?.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val pw = v.picture.width.toFloat(); val ph = v.picture.height.toFloat()
        val s = min(constraints.maxWidth / pw, constraints.maxHeight / ph)
        Box(Modifier.size(with(androidx.compose.ui.platform.LocalDensity.current) { (pw * s).toDp() },
            with(androidx.compose.ui.platform.LocalDensity.current) { (ph * s).toDp() }).clip(RoundedCornerShape(10.dp))) {
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (result != null) Image(result, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (v.boxes.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                val k = size.width / pw
                for (b in v.boxes) {
                    drawRect(accent, Offset(b.left * k, b.top * k), Size((b.right - b.left) * k, (b.bottom - b.top) * k),
                        style = Stroke(2.dp.toPx()))
                }
            }
            if (v.boxes.isNotEmpty()) {
                // labels of the boxes, placed above each one
                val k = with(androidx.compose.ui.platform.LocalDensity.current) { (pw * s).toDp() } / pw
                for (b in v.boxes.take(12)) {
                    Text("${b.label} %.0f%%".format(b.score * 100),
                        modifier = Modifier.padding(start = k * b.left, top = (k * b.top - 14.dp).coerceAtLeast(0.dp))
                            .background(accent.copy(alpha = 0.85f)).padding(horizontal = 3.dp),
                        style = TextStyle(color = Color.Black, fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
                }
            }
            if (v.labels.isNotEmpty()) Labels(v.labels, accent, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

@Composable
private fun SideBySide(v: AiVisual, left: String, right: String, accent: Color, pixelatedLeft: Boolean) {
    val picture = remember(v.picture) { v.picture.asImageBitmap() }
    val result = remember(v.result) { v.result?.asImageBitmap() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        for ((img, label, crisp) in listOf(Triple(picture, left, pixelatedLeft), Triple(result, right, false))) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                // both panels the full height, whatever their pixel size — the input's pixels stay blocky
                val ratio = img?.let { it.width.toFloat() / it.height } ?: 1f
                Box(Modifier.weight(1f).aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(10.dp)).background(Forge.surface)) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                        filterQuality = if (crisp) FilterQuality.None else FilterQuality.Low)
                }
                Spacer(Modifier.height(6.dp))
                Text(label, style = TextStyle(color = accent, fontSize = 10.sp, fontFamily = InstrumentMono,
                    fontWeight = FontWeight.Black, letterSpacing = 1.sp))
            }
        }
    }
}

@Composable
private fun Labels(labels: List<Pair<String, Float>>, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Forge.bg.copy(alpha = 0.78f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for ((name, p) in labels) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(accent))
                }
                Spacer(Modifier.width(8.dp))
                Text("${name.uppercase()} · %.0f%%".format(p * 100), style = TextStyle(color = Forge.ink0, fontSize = 11.sp,
                    fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
            }
        }
    }
}
