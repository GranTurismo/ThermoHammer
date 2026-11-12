package com.example.thermohammer.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.example.thermohammer.R
import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ui.theme.Ramp
import com.example.thermohammer.ui.theme.aiColor
import com.example.thermohammer.ui.theme.cpuColor
import com.example.thermohammer.ui.theme.llmColor
import com.example.thermohammer.ui.theme.memColor
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// SHARE CARD — "Can your phone beat this?"
//
//   A 1080 × 1350 PNG drawn straight onto a Canvas (never a screenshot), in the
//   Forge colours and fonts. The curve and the section bars are drawn by the
//   same code the presentation screen uses (through Compose's nativeCanvas), at
//   a scale `s` (1 = the card's own pixels).
// ─────────────────────────────────────────────────────────────────────────────

/** The bundled fonts as Typefaces for a plain Canvas. */
class CardFonts(context: Context) {
    val mono: Typeface = font(context, R.font.jetbrainsmono_regular, Typeface.MONOSPACE)
    val monoBold: Typeface = font(context, R.font.jetbrainsmono_bold, Typeface.MONOSPACE)
    val monoBlack: Typeface = font(context, R.font.jetbrainsmono_extrabold, Typeface.MONOSPACE)
    val display: Typeface = font(context, R.font.spacegrotesk_700, Typeface.DEFAULT_BOLD)
    val displayMedium: Typeface = font(context, R.font.spacegrotesk_500, Typeface.DEFAULT)

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        try { ResourcesCompat.getFont(context, id) ?: fallback } catch (_: Exception) { fallback }
}

/** Each section's accent, as on the result screen. */
fun sectionColor(name: String): Color = when (name) {
    "CPU" -> cpuColor()
    "MEM" -> memColor()
    "GPU" -> Forge.phaseCalibration
    "UX" -> Forge.phaseMeasured
    "AI" -> aiColor()
    else -> llmColor()
}

object RunCardArt {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    private const val M = 64f                  // side margins

    /** The share image of [card]. */
    fun render(context: Context, card: RunCard): Bitmap {
        val f = CardFonts(context)
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        c.drawColor(Forge.bg.toArgb())

        // a faint glow of the hero colour behind the number, and the instrument's corner ticks
        val hero = when (card) {
            is RunCard.Benchmark -> Forge.phaseMeasured
            is RunCard.Stress -> Ramp.forCapacity(card.capacity.toFloat())
        }
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(w * 0.82f, h * 0.36f, w * 0.75f, hero.copy(alpha = 0.10f).toArgb(),
                android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        cornerTicks(c, 28f, 40f, 3f, Forge.ink2.copy(alpha = 0.5f).toArgb())

        // ── header: brand, verified pill ──
        val brand = paint(f.monoBold, 28f, Forge.phaseMeasured.toArgb(), spacing = 0.32f)
        c.drawText("THERMOHAMMER", M, 104f, brand)
        if (card.verified) {
            val p = paint(f.monoBold, 24f, Forge.phaseMeasured.toArgb(), spacing = 0.2f)
            val tw = p.measureText("VERIFIED")
            val pill = RectF(w - M - tw - 44f, 70f, w - M, 116f)
            c.drawRoundRect(pill, 23f, 23f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Forge.phaseMeasured.copy(alpha = 0.12f).toArgb() })
            c.drawRoundRect(pill, 23f, 23f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = Forge.phaseMeasured.copy(alpha = 0.7f).toArgb()
            })
            c.drawText("VERIFIED", pill.left + 22f, 102f, p)
        }

        // ── the device, up to two lines, and what it ran on ──
        var y = 150f
        val name = StaticLayout.Builder.obtain(card.device, 0, card.device.length,
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.display; textSize = 64f; color = Forge.ink0.toArgb() },
                (w - 2 * M).toInt())
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.0f).build()
        c.save(); c.translate(M, y); name.draw(c); c.restore()
        y += name.height + 46f
        c.drawText(ellipsize(card.subLine, paint(f.mono, 30f, Forge.ink2.toArgb()), w - 2 * M), M, y,
            paint(f.mono, 30f, Forge.ink2.toArgb()))

        // ── footer: the dare and the link ──
        val footerTop = h - M - 150f
        c.drawLine(M, footerTop, w - M, footerTop, Paint().apply { color = Forge.ink0.copy(alpha = 0.10f).toArgb(); strokeWidth = 2f })
        c.drawText("CAN YOUR PHONE BEAT THIS?", M, h - M - 66f, fit(paint(f.display, 44f, Forge.ink0.toArgb(), spacing = 0.02f),
            "CAN YOUR PHONE BEAT THIS?", w - 2 * M))
        c.drawText(ShareLinks.display(card.url), M, h - M - 8f, fit(paint(f.monoBold, 30f, Forge.phaseMeasured.toArgb()),
            ShareLinks.display(card.url), w - 2 * M))

        // ── the run ──
        val bottom = footerTop - 36f
        when (card) {
            is RunCard.Benchmark -> {
                val total = ShareLinks.points(card.total)
                y += 180f
                c.drawText(total, M - 6f, y, fit(paint(f.monoBlack, 190f, Forge.ink0.toArgb()), total, w - 2 * M))
                y += 58f
                c.drawText("BENCHMARK SCORE", M, y, paint(f.monoBold, 28f, Forge.ink2.toArgb(), spacing = 0.2f))
                y += 34f
                val rowH = ((bottom - y) / 6f).coerceIn(56f, 84f)
                drawSections(c, RectF(M, y, w - M, y + rowH * card.sections.size), card, 1f, f)
            }
            is RunCard.Stress -> {
                val kept = "${card.capacity.roundToInt()}%"
                y += 180f
                c.drawText(kept, M - 8f, y, fit(paint(f.display, 190f, Ramp.forCapacity(card.capacity.toFloat()).toArgb()), kept, w - 2 * M))
                y += 58f
                c.drawText(ellipsize(card.label, paint(f.monoBold, 28f, Forge.ink2.toArgb(), spacing = 0.12f), w - 2 * M), M, y,
                    paint(f.monoBold, 28f, Forge.ink2.toArgb(), spacing = 0.12f))
                y += 36f
                val cardH = (bottom - 56f - y).coerceIn(300f, 440f)
                val box = RectF(M, y, w - M, y + cardH)
                c.drawRoundRect(box, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Forge.surface.toArgb() })
                c.drawRoundRect(box, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f; color = Forge.ink0.copy(alpha = 0.08f).toArgb()
                })
                drawCurve(c, box, card, 1f, f)
                c.drawText(card.onsetText, M, box.bottom + 48f, paint(f.monoBold, 30f,
                    (if (card.onsetSec != null) Ramp.at(0.6f) else Forge.phaseMeasured).toArgb()))
            }
        }
        return bmp
    }

    /**
     * The stress curve inside [r]: % of baseline over time, y from 0 to max(110, peak), a dashed 100 % line and the area
     * under the curve tinted along the thermal ramp. [s] scales strokes and labels (1 = card pixels).
     */
    fun drawCurve(c: Canvas, r: RectF, card: RunCard.Stress, s: Float, f: CardFonts) {
        val plot = RectF(r.left + 92f * s, r.top + 34f * s, r.right - 30f * s, r.bottom - 60f * s)
        val yMax = card.yMax
        fun yOf(v: Double) = plot.bottom - (v / yMax).toFloat().coerceIn(0f, 1f) * plot.height()
        val label = paint(f.mono, 22f * s, Forge.ink2.toArgb(), align = Paint.Align.RIGHT)

        // grid: 0 % and 50 % hairlines, the dashed 100 % line
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Forge.ink0.copy(alpha = 0.08f).toArgb(); strokeWidth = 2f * s }
        for (g in listOf(0.0, 50.0)) {
            c.drawLine(plot.left, yOf(g), plot.right, yOf(g), grid)
            c.drawText("${g.toInt()}%", plot.left - 14f * s, yOf(g) + 8f * s, label)
        }
        c.drawLine(plot.left, yOf(100.0), plot.right, yOf(100.0), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Forge.ink1.copy(alpha = 0.6f).toArgb(); strokeWidth = 3f * s; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(14f * s, 10f * s), 0f)
        })
        c.drawText("100%", plot.left - 14f * s, yOf(100.0) + 8f * s, label)
        c.drawText("0:00", plot.left, plot.bottom + 44f * s, paint(f.mono, 22f * s, Forge.ink2.toArgb()))
        c.drawText("${card.minutes}:00", plot.right, plot.bottom + 44f * s, label)

        val pts = card.curve
        if (pts.isEmpty()) {
            c.drawText("NO CURVE FOR THIS RUN", plot.centerX(), plot.centerY(),
                paint(f.monoBold, 24f * s, Forge.ink2.toArgb(), align = Paint.Align.CENTER))
            return
        }
        val n = pts.size
        fun xOf(i: Int) = if (n == 1) plot.left else plot.left + i.toFloat() / (n - 1) * plot.width()
        val line = Path()
        pts.forEachIndexed { i, v -> if (i == 0) line.moveTo(xOf(i), yOf(v)) else line.lineTo(xOf(i), yOf(v)) }
        if (n == 1) line.lineTo(plot.right, yOf(pts[0]))
        val lastX = if (n == 1) plot.right else xOf(n - 1)

        // colour along time = the thermal ramp of each point's level
        val ramp = pts.map { Ramp.forCapacity(it.toFloat()) }.let { if (it.size == 1) it + it else it }
        val stops = FloatArray(ramp.size) { it.toFloat() / (ramp.size - 1) }
        fun along(alpha: Float) = LinearGradient(plot.left, 0f, lastX.coerceAtLeast(plot.left + 1f), 0f,
            ramp.map { it.copy(alpha = alpha).toArgb() }.toIntArray(), stops, Shader.TileMode.CLAMP)

        val area = Path(line).apply { lineTo(lastX, plot.bottom); lineTo(plot.left, plot.bottom); close() }
        val fade = LinearGradient(0f, plot.top, 0f, plot.bottom, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT,
            Shader.TileMode.CLAMP)
        c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = ComposeShader(along(0.34f), fade, PorterDuff.Mode.DST_IN) })
        c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = along(1f); style = Paint.Style.STROKE; strokeWidth = 5f * s
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })

        // where it began to throttle
        card.onsetSec?.let { onset ->
            val x = plot.left + (onset / (card.minutes * 60f)).coerceIn(0f, 1f) * plot.width()
            c.drawLine(x, plot.top, x, plot.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ramp.at(0.68f).copy(alpha = 0.8f).toArgb(); strokeWidth = 2.5f * s; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(8f * s, 8f * s), 0f)
            })
        }
    }

    /** The six section rows inside [r], evenly spaced: name, a bar of value / max(12000, largest section), value. */
    fun drawSections(c: Canvas, r: RectF, card: RunCard.Benchmark, s: Float, f: CardFonts) {
        if (card.sections.isEmpty()) return
        val rowH = r.height() / card.sections.size
        val nameW = 150f * s
        val valueW = 190f * s
        val barH = (rowH * 0.28f).coerceIn(10f * s, 24f * s)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Forge.ink0.copy(alpha = 0.07f).toArgb() }
        val namePaint = paint(f.monoBlack, 32f * s, 0, spacing = 0.06f)
        val valuePaint = paint(f.monoBold, 36f * s, Forge.ink0.toArgb(), align = Paint.Align.RIGHT)
        for ((i, sec) in card.sections.withIndex()) {
            val mid = r.top + rowH * (i + 0.5f)
            val color = sectionColor(sec.name).toArgb()
            namePaint.color = color
            c.drawText(sec.name, r.left, mid + 11f * s, namePaint)
            val bar = RectF(r.left + nameW, mid - barH / 2, r.right - valueW, mid + barH / 2)
            c.drawRoundRect(bar, barH / 2, barH / 2, track)
            val frac = (sec.points.toFloat() / card.barMax).coerceIn(0.02f, 1f)
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * frac, bar.bottom), barH / 2, barH / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            c.drawText(ShareLinks.points(sec.points), r.right, mid + 13f * s, valuePaint)
        }
    }

    fun paint(tf: Typeface, size: Float, color: Int, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; letterSpacing = spacing; textAlign = align
        }

    /** Shrinks [p] until [text] fits [maxW]. */
    private fun fit(p: Paint, text: String, maxW: Float): Paint {
        while (p.measureText(text) > maxW && p.textSize > 12f) p.textSize *= 0.95f
        return p
    }

    private fun ellipsize(text: String, p: Paint, maxW: Float): String =
        TextUtils.ellipsize(text, TextPaint(p), maxW, TextUtils.TruncateAt.END).toString()

    private fun cornerTicks(c: Canvas, inset: Float, len: Float, stroke: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = stroke }
        val l = inset; val t = inset; val r = WIDTH - inset; val b = HEIGHT - inset
        c.drawLine(l, t, l + len, t, p); c.drawLine(l, t, l, t + len, p)
        c.drawLine(r, t, r - len, t, p); c.drawLine(r, t, r, t + len, p)
        c.drawLine(l, b, l + len, b, p); c.drawLine(l, b, l, b - len, p)
        c.drawLine(r, b, r - len, b, p); c.drawLine(r, b, r, b - len, p)
    }
}
