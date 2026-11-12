package com.example.thermohammer.ui.overlays

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.ui.share.CardFonts
import com.example.thermohammer.ui.share.RunCard
import com.example.thermohammer.ui.share.RunCardArt
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// PRESENT — a run at presentation size, for filming and screenshots
//
//   Full screen (its own window above the tab bar, system bars hidden), the
//   screen kept awake. Only the device, one line of context and the data: the
//   stress curve (the share card's own drawing, resized live to the screen) or
//   the total and the six section bars. A small ✕ fades after 3 s and comes
//   back on a tap; Back / Esc close it. Portrait and landscape alike.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PresentDialog(card: RunCard, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,                       // Back and Esc
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false)
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(w, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { view.keepScreenOn = false }
        }
        PresentContent(card, onClose)
    }
}

/** The presentation itself — also what the render test draws. */
@Composable
fun PresentContent(card: RunCard, onClose: () -> Unit) {
    var closeShown by remember { mutableStateOf(true) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        closeShown = true
        delay(3_000)
        closeShown = false
    }
    Box(
        Modifier.fillMaxSize().background(Forge.bg).forgeGrain()
            .pointerInput(Unit) { detectTapGestures { taps++ } }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            val w = maxWidth
            val h = maxHeight
            val landscape = w > h
            if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(0.38f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Heading(card, nameSize = 30f)
                    Spacer(Modifier.height(20.dp))
                    Hero(card, h * 0.30f)
                }
                Box(Modifier.weight(0.62f).fillMaxHeight()) { Data(card) }
            } else Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(28.dp))
                Heading(card, nameSize = 34f)
                Spacer(Modifier.height(18.dp))
                Hero(card, h * 0.16f)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) { Data(card) }
            }
        }
        Text("THERMOHAMMER", style = TextStyle(color = Forge.phaseMeasured.copy(alpha = 0.8f), fontSize = 9.sp,
            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp))
        AnimatedVisibility(closeShown, Modifier.align(Alignment.TopEnd).padding(10.dp),
            enter = fadeIn(tween(200)), exit = fadeOut(tween(600))) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Forge.ink0.copy(alpha = 0.10f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Text("✕", color = Forge.ink0, fontSize = 13.sp, fontWeight = FontWeight.Black) }
        }
    }
}

/** The device, big, and one line: OS · date · test settings. */
@Composable
private fun Heading(card: RunCard, nameSize: Float) {
    Text(card.device, style = TextStyle(color = Forge.ink0, fontSize = nameSize.sp, fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold, lineHeight = (nameSize * 1.1f).sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
    Text(listOfNotNull(card.os, card.date, card.settings).joinToString(" · "), style = ThermoType.data(12f, Forge.ink1),
        maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** The total or the capacity, as large as the width it gets and [height] allow. */
@Composable
private fun Hero(card: RunCard, height: Dp) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val text = when (card) {
        is RunCard.Benchmark -> ShareLinks.points(card.total)
        is RunCard.Stress -> "${card.capacity.roundToInt()}%"
    }
    // a glyph is at most ~0.68 em wide in either font (JetBrains Mono 0.6, Space Grotesk's % wider); the line ~1.15 em tall
    val em = minOf(maxWidth / (text.length * 0.68f), height / 1.15f, 190.dp)
    val size = with(density) { em.toSp() }
    Column { HeroText(card, text, size) }
}

@Composable
private fun HeroText(card: RunCard, text: String, size: androidx.compose.ui.unit.TextUnit) {
    when (card) {
        is RunCard.Benchmark -> {
            Text(text, style = TextStyle(color = Forge.ink0, fontSize = size, fontFamily = InstrumentMono, fontWeight = FontWeight.Black),
                maxLines = 1, softWrap = false)
            Text("BENCHMARK SCORE", style = ThermoType.label(11f))
        }
        is RunCard.Stress -> {
            Text(text, style = TextStyle(color = Ramp.forCapacity(card.capacity.toFloat()), fontSize = size, fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false)
            Text(card.label, style = ThermoType.label(11f))
            Spacer(Modifier.height(4.dp))
            Text(card.onsetText, style = ThermoType.data(13f, if (card.onsetSec != null) Ramp.at(0.6f) else Forge.phaseMeasured,
                FontWeight.Bold))
        }
    }
}

/** The curve or the section bars, filling what is left — drawn by the share card's own code, rescaled. */
@Composable
private fun Data(card: RunCard) {
    val context = LocalContext.current
    val fonts = remember { CardFonts(context) }
    val density = LocalDensity.current.density
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { c ->
            when (card) {
                is RunCard.Stress -> RunCardArt.drawCurve(c.nativeCanvas, RectF(0f, 0f, size.width, size.height), card,
                    (density * 0.55f).coerceAtMost(size.minDimension / 500f).coerceAtLeast(0.6f), fonts)
                is RunCard.Benchmark -> {
                    val rows = card.sections.size.coerceAtLeast(1)
                    val rowH = (size.height / rows).coerceAtMost(110f * density)
                    // the scale that fits both the rows' height and the width (name + value columns ≈ 340 card px)
                    val s = minOf(rowH / 84f, size.width * 0.62f / 340f, density * 0.9f)
                    val top = (size.height - rowH * rows) / 2
                    RunCardArt.drawSections(c.nativeCanvas, RectF(0f, top, size.width, top + rowH * rows), card, s, fonts)
                }
            }
        }
    }
}
