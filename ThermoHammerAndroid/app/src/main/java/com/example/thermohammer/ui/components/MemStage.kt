package com.example.thermohammer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.MemTest
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.engine.mem.MemAccess
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ui.theme.InstrumentMono
import com.example.thermohammer.ui.theme.memColor
import kotlin.math.exp
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// MEM STAGE — the test's measurements (done, running, waiting) over a block
// map of the RAM buffer or the test file: every core's stream sweeping its own
// lane, sequential transfers walking the file, random 4 KB blocks flashing, and
// the latency test's pointer hopping from block to block. Reads in copper,
// writes in coral.
// ─────────────────────────────────────────────────────────────────────────────

private val WriteColor = Color(0xFFFF7A6B)
private const val COLS = 32
private const val ROWS = 10

@Composable
fun MemBenchView(state: StressState) {
    val test = state.memTest ?: return
    val accent = memColor()
    val current = test.labels.indexOf(state.memMeasure).coerceAtLeast(0)
    Column(
        Modifier.fillMaxSize().background(Forge.bg).padding(horizontal = 48.dp, vertical = 84.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((i, label) in test.labels.withIndex()) {
                val done = i < current
                val now = i == current
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (now) accent.copy(alpha = 0.18f) else Forge.surface)
                        .border(1.dp, if (now) accent else if (done) accent.copy(alpha = 0.4f) else Forge.hairline,
                            RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text((if (done) "✓ " else "") + label, style = TextStyle(
                        color = if (now) accent else if (done) Forge.ink1 else Forge.ink2, fontSize = 11.sp,
                        fontFamily = InstrumentMono, fontWeight = if (now) FontWeight.Black else FontWeight.Bold))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        BlockMap(state.memAccess, lanes = if (test == MemTest.RAM) ROWS else 1, accent = accent,
            modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(COLS / ROWS.toFloat() * 0.9f))
        Spacer(Modifier.height(12.dp))
        Text(state.memDetail, style = TextStyle(color = Forge.ink2, fontSize = 9.sp, fontFamily = InstrumentMono, letterSpacing = 1.5.sp))
    }
}

/** The buffer or file as COLS × ROWS blocks; each block glows when touched and fades. */
@Composable
private fun BlockMap(access: MemAccess?, lanes: Int, accent: Color, modifier: Modifier) {
    val heat = remember { FloatArray(COLS * ROWS) }
    val written = remember { BooleanArray(COLS * ROWS) }
    val trail = remember { ArrayDeque<Int>() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(access, lanes) {
        val rnd = Random(11)
        var head = 0f
        var last = 0L
        var hopIn = 0f
        heat.fill(0f)
        trail.clear()
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val fade = exp(-dt * 5f)
            for (i in heat.indices) heat[i] *= fade
            fun touch(i: Int, write: Boolean) { heat[i] = 1f; written[i] = write }
            when (access) {
                MemAccess.READ, MemAccess.WRITE -> {
                    // RAM: every lane (a core) streams its own row; storage: one transfer walks the whole file
                    val write = access == MemAccess.WRITE
                    val span = if (lanes > 1) COLS else COLS * ROWS
                    val from = head.toInt()
                    head = (head + dt * if (lanes > 1) 26f else 70f) % span
                    var c = from
                    while (c != head.toInt()) {
                        if (lanes > 1) for (r in 0 until ROWS) touch(r * COLS + (c + r * 5) % COLS, write) else touch(c, write)
                        c = (c + 1) % span
                    }
                }
                MemAccess.COPY -> {
                    // top half read, bottom half written at the same place
                    val from = head.toInt()
                    head = (head + dt * 26f) % COLS
                    var c = from
                    while (c != head.toInt()) {
                        for (r in 0 until ROWS / 2) {
                            touch(r * COLS + (c + r * 5) % COLS, false)
                            touch((r + ROWS / 2) * COLS + (c + r * 5) % COLS, true)
                        }
                        c = (c + 1) % COLS
                    }
                }
                MemAccess.RANDOM_READ, MemAccess.RANDOM_WRITE ->
                    repeat((dt * 160).toInt().coerceAtLeast(1)) { touch(rnd.nextInt(heat.size), access == MemAccess.RANDOM_WRITE) }
                MemAccess.CHASE -> {
                    // one access at a time: the next address is only known when this one arrives
                    hopIn -= dt
                    if (hopIn <= 0f) {
                        hopIn = 0.07f
                        val next = rnd.nextInt(heat.size)
                        touch(next, false)
                        trail.addLast(next)
                        if (trail.size > 7) trail.removeFirst()
                    }
                }
                null -> {}
            }
            frame = now
        }
    }
    Canvas(modifier) {
        frame.let { }   // redraw every frame
        val gap = 3.dp.toPx()
        val cw = (size.width - gap * (COLS - 1)) / COLS
        val ch = (size.height - gap * (ROWS - 1)) / ROWS
        val radius = CornerRadius(2.dp.toPx())
        fun center(i: Int) = Offset((i % COLS) * (cw + gap) + cw / 2, (i / COLS) * (ch + gap) + ch / 2)
        for (i in heat.indices) {
            val x = (i % COLS) * (cw + gap)
            val y = (i / COLS) * (ch + gap)
            drawRoundRect(Forge.surface, Offset(x, y), Size(cw, ch), radius)
            val h = heat[i]
            if (h > 0.02f) drawRoundRect((if (written[i]) WriteColor else accent).copy(alpha = 0.15f + 0.85f * h),
                Offset(x, y), Size(cw, ch), radius)
        }
        if (access == MemAccess.CHASE) {
            val pts = trail.toList()
            for (k in 1 until pts.size) {
                drawLine(accent.copy(alpha = 0.2f + 0.8f * k / pts.size), center(pts[k - 1]), center(pts[k]),
                    strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}
