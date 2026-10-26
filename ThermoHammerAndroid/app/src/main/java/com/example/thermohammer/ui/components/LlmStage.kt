package com.example.thermohammer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.engine.llm.LlmBench
import com.example.thermohammer.engine.llm.LlmPhase
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ui.theme.InstrumentMono
import com.example.thermohammer.ui.theme.llmColor

// ─────────────────────────────────────────────────────────────────────────────
// LLM STAGE — the context the model works on: the 512-token prompt lighting
// up as each pass reads it in one batch, then the reply written token by token
// (the ids, coloured per token). The weights come from a seed, so the words are
// random; the work is the real model's.
// ─────────────────────────────────────────────────────────────────────────────

private const val PROMPT_COLS = 32
private const val PROMPT_ROWS = 16
private const val REPLY_COLS = 8

@Composable
fun LlmBenchView(state: StressState) {
    val accel = state.llmAccel ?: return
    val accent = llmColor()
    val label = TextStyle(fontSize = 9.sp, fontFamily = InstrumentMono, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Column(
        Modifier.fillMaxSize().background(Forge.bg).padding(horizontal = 40.dp, vertical = 78.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for (a in LlmAccel.entries) {
                val done = a.ordinal < accel.ordinal
                val now = a == accel
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (now) accent.copy(alpha = 0.18f) else Color.Transparent)
                        .border(1.dp, if (now) accent else Forge.ink0.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text((if (done) "✓ " else "") + a.label, style = label.copy(color = if (now) accent else Forge.ink2))
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(LlmBench.MODEL + " · 1.8 GB · 28 LAYERS", style = label.copy(color = Forge.ink1, fontWeight = FontWeight.Normal))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                Text("PROMPT · 512 TOKENS · ONE BATCH" + if (state.llmPrompts > 0) " · ×${state.llmPrompts}" else "",
                    style = label.copy(color = if (state.llmPhase == LlmPhase.PREFILL) accent else Forge.ink2))
                Spacer(Modifier.height(6.dp))
                PromptGrid(state.llmPhase, state.llmPrompts, accent, Modifier.fillMaxWidth().weight(1f))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("REPLY · TOKEN BY TOKEN", style = label.copy(color = if (state.llmPhase == LlmPhase.DECODE) accent else Forge.ink2))
                Spacer(Modifier.height(6.dp))
                ReplyGrid(state.llmReply, Modifier.fillMaxWidth().weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("WEIGHTS FROM A SEED: THE WORDS ARE RANDOM, THE WORK IS THE REAL MODEL'S",
            style = label.copy(color = Forge.ink2, fontWeight = FontWeight.Normal))
    }
}

/** The prompt's 512 tokens: dim until read; each prefill pass sweeps through them at once (one batch). */
@Composable
private fun PromptGrid(phase: LlmPhase, prompts: Int, accent: Color, modifier: Modifier) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(prompts) {
        if (prompts > 0) { sweep.snapTo(0f); sweep.animateTo(1f, tween(520, easing = LinearEasing)) }
    }
    val read = prompts > 0 || phase == LlmPhase.DECODE
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val cw = (size.width - gap * (PROMPT_COLS - 1)) / PROMPT_COLS
        val ch = (size.height - gap * (PROMPT_ROWS - 1)) / PROMPT_ROWS
        val r = CornerRadius(minOf(cw, ch) * 0.2f)
        val front = sweep.value * PROMPT_COLS * PROMPT_ROWS
        for (i in 0 until PROMPT_COLS * PROMPT_ROWS) {
            val x = (i % PROMPT_COLS) * (cw + gap); val y = (i / PROMPT_COLS) * (ch + gap)
            val alpha = when {
                !read -> 0.08f
                phase == LlmPhase.PREFILL && i < front && i > front - 64 -> 1f    // the batch going through
                else -> 0.42f
            }
            drawRoundRect(accent.copy(alpha = alpha), Offset(x, y), Size(cw, ch), r)
        }
    }
}

/** The reply so far: one chip per generated token, its id in its own colour. */
@Composable
private fun ReplyGrid(reply: List<Int>, modifier: Modifier) {
    val rows = (LlmBench.REPLY + REPLY_COLS - 1) / REPLY_COLS
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until REPLY_COLS) {
                    val k = r * REPLY_COLS + c
                    val token = reply.getOrNull(k)
                    val color = token?.let { tokenColor(it) }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                            .background(color?.copy(alpha = 0.16f) ?: Forge.ink0.copy(alpha = 0.04f))
                            .border(1.dp, color?.copy(alpha = 0.6f) ?: Color.Transparent, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (token != null) Text("$token", style = TextStyle(color = color!!, fontSize = 9.sp,
                            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A steady colour per token id, spread round the hue circle. */
private fun tokenColor(id: Int): Color = Color.hsv((id * 0.618034f % 1f) * 360f, 0.5f, 0.97f)
