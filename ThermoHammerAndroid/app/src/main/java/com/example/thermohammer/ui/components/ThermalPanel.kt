package com.example.thermohammer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// THE THERMAL HORIZON
//
//   · SoC temp as a ramp-colored numeral + sparkline of the run's history
//   · zone sensor-map — every thermal zone as a muted ramp-tinted cell
//   · engraved chips: skin / battery / current draw / perf-per-watt (n/a honest)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ThermalMonitoringPanel(state: StressState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    // UI-side temp history for the sparkline (per-second samples while panel lives)
    val tempHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.cpuTemp) {
        if (state.cpuTemp > 0f) {
            tempHistory.add(state.cpuTemp)
            if (tempHistory.size > 120) tempHistory.removeAt(0)
        }
    }

    // Power telemetry from the newest stamp
    val lastStamp = state.recordedStamps.lastOrNull()
    val drawMw = lastStamp?.let {
        if (it.battCurrentUa != null && it.battVoltageMv > 0)
            it.battCurrentUa.toDouble() * it.battVoltageMv / 1_000_000.0 else null
    }
    val perfWatt = state.scorecard?.perfPerWatt

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Forge.surface)
            .cornerTicks(Forge.ink2.copy(alpha = 0.5f))
            .border(1.dp, Forge.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("THERMAL HORIZON", style = ThermoType.title(10f))
            Spacer(Modifier.weight(1f))
            if (state.thermalSensors.isNotEmpty()) {
                Text(
                    if (expanded) "COLLAPSE ▲" else "ALL ZONES ▼",
                    style = ThermoType.label(7.5f, Forge.phaseMeasured.copy(alpha = 0.8f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Hero row: SoC numeral + sparkline ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SOC PACKAGE", style = ThermoType.label(7.5f))
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (state.cpuTemp > 0f) "%.1f".format(state.cpuTemp) else "--",
                        style = TextStyle(
                            color = if (state.cpuTemp > 0f) Ramp.forTemp(state.cpuTemp) else Forge.ink2,
                            fontSize = 34.sp, fontFamily = DisplayFont, fontWeight = FontWeight.Medium
                        )
                    )
                    Text(" °C", style = ThermoType.data(14f, Forge.ink1))
                }
            }
            Spacer(Modifier.weight(1f))
            // Sparkline
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(44.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (tempHistory.size < 2) return@Canvas
                    val w = size.width; val h = size.height
                    val tMin = tempHistory.min()
                    val tMax = maxOf(tempHistory.max(), tMin + 1f)
                    fun px(i: Int) = i.toFloat() / (tempHistory.size - 1) * w
                    fun py(t: Float) = h - ((t - tMin) / (tMax - tMin)) * (h - 4f) - 2f

                    val path = Path()
                    path.moveTo(px(0), py(tempHistory[0]))
                    for (i in 1 until tempHistory.size) path.lineTo(px(i), py(tempHistory[i]))
                    drawPath(
                        path,
                        Brush.horizontalGradient(
                            listOf(Forge.ink2.copy(alpha = 0.6f), Ramp.forTemp(tempHistory.last()))
                        ),
                        style = Stroke(2.5f)
                    )
                    // latest point
                    drawCircle(Ramp.forTemp(tempHistory.last()), 3.5f, Offset(px(tempHistory.size - 1), py(tempHistory.last())))
                    // min/max engravings
                }
                Text(
                    "%.0f".format(tempHistory.maxOrNull() ?: 0f),
                    style = ThermoType.annotation(6.5f),
                    modifier = Modifier.align(Alignment.TopEnd)
                )
                Text(
                    "%.0f".format(tempHistory.minOrNull() ?: 0f),
                    style = ThermoType.annotation(6.5f),
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Zone sensor-map ───────────────────────────────────────────────────
        if (state.thermalSensors.isNotEmpty()) {
            val zones = state.thermalSensors.sortedByDescending { it.second }
            val visible = if (expanded) zones else zones.take(8)
            // flow rows of 2
            visible.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pair.forEach { (name, temp) ->
                        ZoneCell(name = name, tempC = temp, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (!expanded && zones.size > 8) {
                Text(
                    "+${zones.size - 8} more zones — expand to inspect",
                    style = ThermoType.annotation(7.5f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Engraved telemetry chips ──────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TelemetryChip(
                label = "BATTERY",
                value = if (state.batteryTemp > 0f) "%.1f°C".format(state.batteryTemp) else "--",
                color = if (state.batteryTemp > 0f) Ramp.forTemp(state.batteryTemp) else Forge.ink1,
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "CURRENT DRAW",
                value = lastStamp?.battCurrentUa?.let { "%.0f mA".format(it / 1000.0) } ?: "n/a",
                color = Forge.ink1,
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "POWER",
                value = drawMw?.let { "%.2f W".format(it) } ?: "n/a",
                color = Forge.ink1,
                modifier = Modifier.weight(1f)
            )
            TelemetryChip(
                label = "PERF/WATT",
                value = perfWatt?.let { "%.1fk".format(it / 1000f) } ?: "n/a",
                color = if (perfWatt != null) Forge.phaseMeasured else Forge.ink1,
                modifier = Modifier.weight(1f)
            )
        }

        // ── Full expanded zone list ───────────────────────────────────────────
        if (expanded && state.thermalSensors.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.thermalSensors.sortedByDescending { it.second }.forEach { (name, temp) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                name.replace("-", " ").replace("_", " ").uppercase(),
                                style = ThermoType.annotation(8.5f, Forge.ink1)
                            )
                            Text(
                                "%.1f°C".format(temp),
                                style = ThermoType.data(9.5f, Ramp.forTemp(temp), FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneCell(name: String, tempC: Float, modifier: Modifier = Modifier) {
    val tint = Ramp.forTemp(tempC)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name.take(14).uppercase(),
            style = ThermoType.annotation(7f, Forge.ink1),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            "%.1f°".format(tempC),
            style = ThermoType.data(9f, tint, FontWeight.Bold)
        )
    }
}

@Composable
private fun TelemetryChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Forge.raised)
            .border(1.dp, Forge.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(label, style = ThermoType.annotation(6.5f), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, style = ThermoType.data(10.5f, color, FontWeight.Bold), maxLines = 1)
    }
}
