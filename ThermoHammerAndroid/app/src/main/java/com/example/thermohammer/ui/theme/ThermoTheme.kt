package com.example.thermohammer.ui.theme

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.R
import com.example.thermohammer.engine.Attribution
import com.example.thermohammer.engine.RunPhase
import com.example.thermohammer.engine.ThermalState
import com.example.thermohammer.engine.ValidityFlag
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// THERMOHAMMER FORGE — design tokens
// "The forge is dark. The metal glows. The instrument tells the truth beautifully."
// ─────────────────────────────────────────────────────────────────────────────

object Forge {
    val bg        = Color(0xFF06090B)  // deepest layer — inside of a cold forge
    val surface   = Color(0xFF0B1117)  // card surface
    val raised    = Color(0xFF111A21)  // raised panels / overlay cards
    val interact  = Color(0xFF1A2630)  // interactive elements / selected states
    val hairline  = Color.White.copy(alpha = 0.08f)
    val ink0      = Color(0xFFEAF4F4)  // primary data text
    val ink1      = Color(0xFF9FB4BC)  // secondary text
    val ink2      = Color(0xFF5A6B73)  // engraved labels / annotations

    // Phase accents
    val phasePreFlight   = Color(0xFF4A9EFF)  // signal blue
    val phaseCooldown    = Color(0xFF5BC8FF)  // ice blue — shedding heat
    val phaseWarmup      = Color(0xFF5A6B73)  // dormant steel
    val phaseCalibration = Color(0xFF9D7BFF)  // arc violet
    val phaseMeasured    = Color(0xFF3DE8C2)  // forge teal
}

/** The Thermal Ramp — the app's signature. Continuous position 0..1. */
object Ramp {
    private val stops = listOf(
        0.00f to Color(0xFF3DE8C2),  // teal        — cool / full capacity
        0.25f to Color(0xFF9EE86A),  // green
        0.50f to Color(0xFFFFB545),  // amber       — stressed
        0.68f to Color(0xFFFF6A3D),  // ember
        0.85f to Color(0xFFFF3D5E),  // alarm       — critical
        1.00f to Color(0xFFFFE9D6),  // molten      — white-hot
    )

    /** t = 0 → cool teal (good), t = 1 → molten (hot/severe). */
    fun at(t: Float): Color {
        val c = t.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (c <= p1) {
                val f = if (p1 > p0) (c - p0) / (p1 - p0) else 0f
                return lerp(c0, c1, f)
            }
        }
        return stops.last().second
    }

    /** Map a delivered-capacity percentage (100 = perfect) onto the ramp. */
    fun forCapacity(pct: Float): Color = at(((100f - pct) / 55f).coerceIn(0f, 1f))

    /** Map a temperature in °C onto the ramp (25° cool → 80° molten). */
    fun forTemp(c: Float): Color = at(((c - 25f) / 55f).coerceIn(0f, 1f))

    /** Soft glow color for bloom effects. */
    fun glow(t: Float, alpha: Float = 0.35f): Color = at(t).copy(alpha = alpha)
}

fun phaseColor(phase: RunPhase): Color = when (phase) {
    RunPhase.IDLE        -> Forge.ink2
    RunPhase.COOLDOWN    -> Forge.phaseCooldown
    RunPhase.WARMUP      -> Forge.phaseWarmup
    RunPhase.CALIBRATION -> Forge.phaseCalibration
    RunPhase.MEASURED    -> Forge.phaseMeasured
}

fun thermalStateColor(state: ThermalState): Color = when (state) {
    ThermalState.NOMINAL  -> Ramp.at(0.05f)
    ThermalState.FAIR     -> Ramp.at(0.30f)
    ThermalState.SERIOUS  -> Ramp.at(0.62f)
    ThermalState.CRITICAL -> Ramp.at(0.85f)
}

fun thermalStateName(state: ThermalState): String = when (state) {
    ThermalState.NOMINAL  -> "NOMINAL"
    ThermalState.FAIR     -> "FAIR"
    ThermalState.SERIOUS  -> "SERIOUS"
    ThermalState.CRITICAL -> "CRITICAL"
}

// ── Attribution — "why did throughput drop" colors & labels ────────────────────

fun attributionColor(a: Attribution): Color = when (a) {
    Attribution.NONE       -> Ramp.at(0.05f)
    Attribution.DVFS_CAP   -> Ramp.at(0.68f)
    Attribution.GOVERNOR   -> Ramp.at(0.50f)
    Attribution.MIGRATION  -> Forge.phaseCalibration
    Attribution.HOTPLUG    -> Ramp.at(0.85f)
    Attribution.CONTENTION -> Forge.phasePreFlight
    Attribution.UNKNOWN    -> Forge.ink2
}

fun attributionLabel(a: Attribution): String = when (a) {
    Attribution.NONE       -> "CLEAN RUN"
    Attribution.DVFS_CAP   -> "FREQ CAP"
    Attribution.GOVERNOR   -> "GOVERNOR CLOCK"
    Attribution.MIGRATION  -> "CORE MIGRATION"
    Attribution.HOTPLUG    -> "CORE OFFLINE"
    Attribution.CONTENTION -> "EXTERNAL LOAD"
    Attribution.UNKNOWN    -> "TELEMETRY LIMITED"
}

fun validityFlagNames(flags: Int): List<String> = buildList {
    if (flags and ValidityFlag.WARM_STARTED     != 0) add("WARM START")
    if (flags and ValidityFlag.INTERFERENCE     != 0) add("INTERFERENCE")
    if (flags and ValidityFlag.POWER_EVENT      != 0) add("POWER EVENT")
    if (flags and ValidityFlag.FREQ_MISSING     != 0) add("NO CLOCK DATA")
    if (flags and ValidityFlag.SHORT_RUN        != 0) add("SHORT RUN")
    if (flags and ValidityFlag.ENV_CAPPED       != 0) add("ENV CAPPED")
    if (flags and ValidityFlag.SUSPICIOUS_BOOST != 0) add("BOOST ANOMALY")
    if (flags and ValidityFlag.COOLDOWN_TIMEOUT != 0) add("COOLDOWN TIMEOUT")
    if (flags and ValidityFlag.BASELINE_EXCEEDED != 0) add("BASELINE EXCEEDED")
}

// ── Typography ─────────────────────────────────────────────────────────────────

/** Instrument voice — JetBrains Mono bundled in res/font. */
val InstrumentMono = FontFamily(
    Font(R.font.jetbrainsmono_regular,   FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium,    FontWeight.Medium),
    Font(R.font.jetbrainsmono_bold,      FontWeight.Bold),
    Font(R.font.jetbrainsmono_extrabold, FontWeight.Black)
)

/** Display voice — Space Grotesk for hero numerals & verdicts. */
val DisplayFont = FontFamily(
    Font(R.font.spacegrotesk_500, FontWeight.Medium),
    Font(R.font.spacegrotesk_700, FontWeight.Bold)
)

object ThermoType {
    fun hero(size: Float = 64f) = TextStyle(
        color = Forge.ink0, fontSize = size.sp, fontFamily = DisplayFont,
        fontWeight = FontWeight.Medium
    )
    fun title(size: Float = 12f, color: Color = Forge.ink1) = TextStyle(
        color = color, fontSize = size.sp, fontFamily = InstrumentMono,
        fontWeight = FontWeight.Black, letterSpacing = (size * 0.08f).sp
    )
    fun label(size: Float = 9f, color: Color = Forge.ink2) = TextStyle(
        color = color, fontSize = size.sp, fontFamily = InstrumentMono,
        fontWeight = FontWeight.Bold, letterSpacing = (size * 0.12f).sp
    )
    fun data(size: Float = 13f, color: Color = Forge.ink0, weight: FontWeight = FontWeight.Medium) = TextStyle(
        color = color, fontSize = size.sp, fontFamily = InstrumentMono, fontWeight = weight
    )
    fun annotation(size: Float = 8f, color: Color = Forge.ink2) = TextStyle(
        color = color, fontSize = size.sp, fontFamily = InstrumentMono,
        letterSpacing = (size * 0.10f).sp
    )
}

// ── Modifiers & drawing helpers ────────────────────────────────────────────────

/** Corner ticks — instrument-bezel L marks instead of a full border. */
fun Modifier.cornerTicks(
    color: Color = Forge.ink2.copy(alpha = 0.6f),
    tickLen: Dp = 10.dp,
    stroke: Dp = 1.dp,
    inset: Dp = 0.dp
): Modifier = drawBehind {
    val l = tickLen.toPx()
    val s = stroke.toPx()
    val i = inset.toPx()
    val w = size.width - i
    val h = size.height - i
    // top-left
    drawLine(color, Offset(i, i), Offset(i + l, i), s)
    drawLine(color, Offset(i, i), Offset(i, i + l), s)
    // top-right
    drawLine(color, Offset(w, i), Offset(w - l, i), s)
    drawLine(color, Offset(w, i), Offset(w, i + l), s)
    // bottom-left
    drawLine(color, Offset(i, h), Offset(i + l, h), s)
    drawLine(color, Offset(i, h), Offset(i, h - l), s)
    // bottom-right
    drawLine(color, Offset(w, h), Offset(w - l, h), s)
    drawLine(color, Offset(w, h), Offset(w, h - l), s)
}

private var grainBitmap: android.graphics.Bitmap? = null

private fun getGrainBitmap(): android.graphics.Bitmap {
    grainBitmap?.let { return it }
    val size = 128
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val px = IntArray(size * size)
    val rnd = Random(42)
    for (i in px.indices) {
        val v = rnd.nextInt(0, 256)
        px[i] = (28 shl 24) or (v shl 16) or (v shl 8) or v // alpha≈11%, monochrome
    }
    bmp.setPixels(px, 0, size, 0, 0, size, size)
    grainBitmap = bmp
    return bmp
}

/** Filmic grain — 2–3% monochrome noise; one shader-tiled fill, zero GPU cost. */
fun Modifier.forgeGrain(): Modifier = this.drawBehind {
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.BitmapShader(
            getGrainBitmap(),
            android.graphics.Shader.TileMode.REPEAT,
            android.graphics.Shader.TileMode.REPEAT
        )
        alpha = 56 // ~22% of the noise pixels' own ~11% alpha → ~2-3% effective
    }
    drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
}

/** Radial glow sprite — pre-baked gradient, cheap alternative to live blur. */
fun DrawScope.bloom(center: Offset, radius: Float, color: Color, strength: Float = 0.35f) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = center, radius = radius
        ),
        radius = radius, center = center
    )
}

/** A dashed-line stroke preset for "potential" / reference lines. */
val PotentialStroke = Stroke(
    width = 2f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
)

// ── Micro components ───────────────────────────────────────────────────────────

/** Engraved instrument label — uppercase spaced mono. */
@Composable
fun Engraved(text: String, color: Color = Forge.ink2, size: Float = 9f) {
    Text(text, style = ThermoType.label(size, color))
}

/** Status LED — hollow (pending) → spinning (checking) → solid (result). */
@Composable
fun StatusLED(color: Color, active: Boolean = true, size: Dp = 8.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        if (active) drawCircle(color) else drawCircle(color.copy(alpha = 0.35f), style = Stroke(1.5f))
    }
}

/**
 * Lerped number ticker — value animates to target instead of snapping.
 * Suffix rendered at 70% size per the type spec.
 */
@Composable
fun TickerText(
    value: Float,
    format: (Float) -> String,
    color: Color = Forge.ink0,
    size: Float = 22f,
    fontFamily: FontFamily = InstrumentMono,
    weight: FontWeight = FontWeight.Bold,
    enabled: Boolean = true
) {
    val animated by animateFloatAsState(
        targetValue = if (enabled) value else value,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "ticker"
    )
    Text(format(animated), style = TextStyle(
        color = color, fontSize = size.sp, fontFamily = fontFamily, fontWeight = weight
    ))
}

// ── Haptics ────────────────────────────────────────────────────────────────────

class ThermoHaptics(private val view: View) {
    private fun fb(constant: Int, minSdk: Int = 1) {
        if (android.os.Build.VERSION.SDK_INT >= minSdk) {
            try { view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) } catch (_: Exception) {}
        }
    }
    /** Subtle tick on every confirmed stamp. */
    fun stamp() = fb(if (android.os.Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    /** Heavy thud on throttle-onset confirmation. */
    fun onset() = fb(HapticFeedbackConstants.LONG_PRESS)
    /** Verdict seals. */
    fun confirmed() = fb(HapticFeedbackConstants.CONFIRM, 30)
    fun rejected()  = fb(HapticFeedbackConstants.REJECT, 30)
    fun tap()       = fb(HapticFeedbackConstants.VIRTUAL_KEY)
}

@Composable
fun rememberThermoHaptics(): ThermoHaptics {
    val view = LocalView.current
    return remember { ThermoHaptics(view) }
}

/** Reduced-motion flag — honors system animator scale. */
@Composable
fun rememberReducedMotion(): Boolean {
    val view = LocalView.current
    return remember {
        try {
            android.provider.Settings.Global.getFloat(
                view.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        } catch (_: Exception) { false }
    }
}
