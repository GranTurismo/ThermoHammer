package com.example.thermohammer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.ClusterTopology
import com.example.thermohammer.engine.CpuCoreFreq
import com.example.thermohammer.ui.theme.*
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// CORE CONSTELLATION — worker throughput grouped over the real CPU topology.
//
//   · per-cluster cards named LITTLE / MID / BIG / PRIME by clock ceiling
//   · per-core radial gauges show scaling_cur_freq ratio (real telemetry)
//   · worker-throughput gauges remain separate + honest "not pinned" footnote
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CoreStatusView(
    coreImpacts: List<Float>,
    cpuFrequencies: List<CpuCoreFreq> = emptyList(),
    topology: ClusterTopology? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Forge.surface)
            .cornerTicks(Forge.ink2.copy(alpha = 0.5f))
            .border(1.dp, Forge.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text("⬡  CPU CONSTELLATION", style = ThermoType.title(10f))
        Spacer(Modifier.height(4.dp))
        Text("core clocks grouped by real cluster topology", style = ThermoType.annotation(7.5f))
        Spacer(Modifier.height(12.dp))

        // ── Cluster constellation ────────────────────────────────────────────
        if (topology != null && cpuFrequencies.isNotEmpty()) {
            val order = topology.maxKHzPerCluster.indices.sortedBy { topology.maxKHzPerCluster[it] }
            order.forEachIndexed { rank, ci ->
                val cores = topology.clusters[ci]
                val maxGHz = topology.maxKHzPerCluster[ci] / 1_000_000.0
                val name = clusterName(rank, order.size)
                ClusterCard(
                    name = name,
                    ghzLabel = "%.2f GHZ".format(maxGHz),
                    cores = cores,
                    freqs = cpuFrequencies,
                    accent = clusterAccent(rank, order.size)
                )
                if (rank != order.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }

        // ── Worker throughput gauges ─────────────────────────────────────────
        if (coreImpacts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("WORKER THROUGHPUT", style = ThermoType.label(8.5f))
            Spacer(Modifier.height(8.dp))
            val rows = coreImpacts.chunked(4)
            rows.forEachIndexed { rowIdx, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { k, impact ->
                        WorkerGauge(
                            index = rowIdx * 4 + k + 1,
                            impact = impact,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "workers are scheduler-managed — not pinned to cores",
                style = ThermoType.annotation(7.5f)
            )
        }
    }
}

private fun clusterName(rank: Int, total: Int): String = when {
    total <= 1 -> "UNIFIED"
    total == 2 -> if (rank == 0) "LITTLE" else "BIG"
    rank == 0 -> "LITTLE"
    rank == total - 1 -> "PRIME"
    else -> "MID"
}

private fun clusterAccent(rank: Int, total: Int): Color = when {
    total <= 1 -> Forge.phaseMeasured
    rank == 0 -> Forge.phasePreFlight        // little = cool
    rank == total - 1 -> Ramp.at(0.62f)      // prime = ember
    else -> Forge.phaseCalibration           // mid = violet
}

@Composable
private fun ClusterCard(
    name: String,
    ghzLabel: String,
    cores: List<Int>,
    freqs: List<CpuCoreFreq>,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised)
            .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = TextStyle(
                    color = accent, fontSize = 9.sp, fontFamily = InstrumentMono,
                    fontWeight = FontWeight.Black, letterSpacing = 1.5.sp
                )
            )
            Text(
                "  ×${cores.size}",
                style = ThermoType.data(9f, Forge.ink1, FontWeight.Bold)
            )
            Spacer(Modifier.weight(1f))
            Text(ghzLabel, style = ThermoType.annotation(8f, accent.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            cores.forEach { core ->
                val f = freqs.getOrNull(core)
                CoreFreqGauge(core = core, freq = f, accent = accent, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Per-core radial gauge — arc = scaling_cur / hw max; wireframe when offline. */
@Composable
private fun CoreFreqGauge(core: Int, freq: CpuCoreFreq?, accent: Color, modifier: Modifier = Modifier) {
    val ratio = ((freq?.percentOfMax ?: 0) / 100f).coerceIn(0f, 1f)
    val cap = freq?.capPercentOfMax
    val online = freq?.isOnline == true

    val animRatio by animateFloatAsState(ratio, tween(400, easing = FastOutSlowInEasing), label = "core_freq_$core")

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(46.dp)) {
                val stroke = 4.dp.toPx()
                val r = (min(size.width, size.height) - stroke) / 2f
                val c = Offset(size.width / 2, size.height / 2)
                val tl = Offset(c.x - r, c.y - r)
                val sz = Size(r * 2, r * 2)

                // track
                drawArc(Forge.hairline, -90f, 360f, false, tl, sz, style = Stroke(stroke))

                if (online) {
                    // cap limit arc — the imposed ceiling marker (throttle evidence)
                    if (cap != null && cap < 100) {
                        drawArc(
                            Ramp.at(0.85f).copy(alpha = 0.5f), -90f, 360f * cap / 100f, false,
                            tl, sz, style = Stroke(stroke + 2f)
                        )
                    }
                    // current freq arc
                    drawArc(
                        Ramp.at(1f - animRatio), -90f, 360f * animRatio, false,
                        tl, sz, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (online) "%d%%".format((animRatio * 100).toInt()) else "OFF",
                    style = TextStyle(
                        color = if (online) Forge.ink0 else Forge.ink2.copy(alpha = 0.5f),
                        fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text("C$core", style = ThermoType.annotation(7f, accent.copy(alpha = 0.8f)))
        if (cap != null && cap < 100 && online) {
            Text("CAP $cap%", style = TextStyle(
                color = Ramp.at(0.85f), fontSize = 6.5.sp,
                fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
            ))
        }
    }
}

/** Worker throughput ring — throughput vs the worker's own calibration baseline. */
@Composable
fun WorkerGauge(index: Int, impact: Float, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(impact, tween(500, easing = FastOutSlowInEasing), label = "worker_$index")
    val isIdle = anim <= 0.5f
    val ringColor = if (isIdle) Forge.ink2.copy(alpha = 0.4f) else Ramp.forCapacity(anim)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.raised)
            .border(1.dp, Forge.hairline, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            Canvas(Modifier.size(60.dp)) {
                val stroke = 5.dp.toPx()
                val r = (min(size.width, size.height) - stroke) / 2f
                val c = Offset(size.width / 2, size.height / 2)
                val tl = Offset(c.x - r, c.y - r)
                val sz = Size(r * 2, r * 2)
                drawArc(Forge.hairline, -90f, 360f, false, tl, sz, style = Stroke(stroke))
                if (!isIdle) {
                    drawArc(
                        ringColor, -90f, maxOf(0.05f, anim / 100f) * 360f, false,
                        tl, sz, style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("W$index", style = ThermoType.annotation(7f))
                Text(
                    when {
                        isIdle -> "IDLE"
                        anim >= 99.5f -> "100%"
                        else -> "-${(100f - anim).toInt()}%"
                    },
                    style = TextStyle(
                        color = if (isIdle) Forge.ink2.copy(alpha = 0.6f) else ringColor,
                        fontSize = 11.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}
