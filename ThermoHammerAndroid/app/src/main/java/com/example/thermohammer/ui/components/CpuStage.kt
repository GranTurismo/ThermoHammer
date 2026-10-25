package com.example.thermohammer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ui.theme.InstrumentMono
import com.example.thermohammer.ui.theme.cpuColor

// ─────────────────────────────────────────────────────────────────────────────
// CPU STAGE — what the screen shows during the CPU tests: the test's kernels
// (done, running, waiting) and every core's clock, which all light up in the
// multi-core test.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CpuBenchView(state: StressState) {
    val test = state.cpuTest ?: return
    val accent = cpuColor()
    val current = (state.cpuProgress * test.labels.size).toInt().coerceIn(0, test.labels.size - 1)
    Column(
        Modifier.fillMaxSize().background(Forge.bg).padding(horizontal = 48.dp, vertical = 96.dp),
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
        Spacer(Modifier.height(28.dp))
        // one bar per core: its clock as a share of its maximum (blank where the kernel hides cpufreq)
        val cores = state.cpuFrequencies
        Row(Modifier.height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            for (c in cores) {
                val pct = (c.percentOfMax ?: 0) / 100f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(22.dp).height(96.dp).clip(RoundedCornerShape(5.dp)).background(Forge.surface),
                        contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(pct.coerceIn(0.02f, 1f)).background(
                            if (c.isOnline) accent.copy(alpha = 0.35f + 0.65f * pct) else Color.Transparent))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("C${c.core}", style = TextStyle(color = Forge.ink2, fontSize = 8.sp, fontFamily = InstrumentMono))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (state.cpuThreads > 1) "${state.cpuThreads} THREADS · EVERY CORE" else "1 THREAD · ONE CORE",
            style = TextStyle(color = Forge.ink2, fontSize = 9.sp, fontFamily = InstrumentMono, letterSpacing = 1.5.sp))
    }
}
