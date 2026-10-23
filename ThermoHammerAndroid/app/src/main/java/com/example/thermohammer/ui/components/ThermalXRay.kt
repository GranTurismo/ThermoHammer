package com.example.thermohammer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.thermohammer.engine.StressState
import com.example.thermohammer.engine.ThermalState
import com.example.thermohammer.ui.theme.*
import com.example.thermohammer.ui.xray.XRayFrame
import com.example.thermohammer.ui.xray.XRayMapper
import com.example.thermohammer.ui.xray.XRayReading
import com.example.thermohammer.ui.xray.XRayRegion
import com.example.thermohammer.ui.xray.XRayUnit
import kotlin.math.max

// ─────────────────────────────────────────────────────────────────────────────
// THERMAL X-RAY
//
//   A schematic phone, seen from the back, with every part we can actually read
//   lit by its own sensor. Rules of the instrument:
//     · a region glows ONLY when a real thermal zone backs it — no interpolation
//       across the body, no invented temperatures
//     · a region with no sensor is hatched and says so
//     · the outline is typical, not measured: positions are schematic, the
//       numbers beside them are not
//     · every callout names the raw kernel zone behind its number
// ─────────────────────────────────────────────────────────────────────────────

private const val BODY_ASPECT = 0.47f          // width / height of the schematic body
private val GUTTER = 88.dp                      // callout column on each side
private val CALLOUT_H = 36.dp
private val BODY_PAD = 16.dp                    // room for the skin-rim bloom

// ── Schematic geometry — everything is a function of the body rect ───────────

private fun Rect.at(ux: Float, uy: Float) = Offset(left + ux * width, top + uy * height)

private const val DIE_CY = 0.305f
private const val DIE_HALF = 0.17f              // of body width

private fun dieQuad(b: Rect, col: Int, row: Int): Rect {
    val half = DIE_HALF * b.width
    val gap = 0.016f * b.width
    val c = b.at(0.5f, DIE_CY)
    return Rect(
        left = if (col == 0) c.x - half else c.x + gap / 2,
        top = if (row == 0) c.y - half else c.y + gap / 2,
        right = if (col == 0) c.x - gap / 2 else c.x + half,
        bottom = if (row == 0) c.y - gap / 2 else c.y + half
    )
}

private fun boardRect(b: Rect) = Rect(b.at(0.07f, 0.185f), b.at(0.93f, 0.47f))
private fun batteryRect(b: Rect) = Rect(b.at(0.10f, 0.51f), b.at(0.90f, 0.885f))
private fun cameraRect(b: Rect) = Rect(b.at(0.08f, 0.035f), b.at(0.46f, 0.15f))
private fun modemRect(b: Rect) = Rect(b.at(0.62f, 0.045f), b.at(0.92f, 0.12f))
private fun chargerRect(b: Rect) = Rect(b.at(0.36f, 0.925f), b.at(0.64f, 0.955f))

private class XRayNode(
    val region: XRayRegion,
    val left: Boolean,              // which gutter holds the callout
    val slot: Float,                // callout centre as a fraction of body height
    val anchor: (Rect) -> Offset    // where the leader line lands on the body
)

private val NODES = listOf(
    XRayNode(XRayRegion.CAMERA, true, 0.07f) { cameraRect(it).center },
    XRayNode(XRayRegion.CPU, true, 0.25f) { dieQuad(it, 0, 0).center },
    XRayNode(XRayRegion.NPU, true, 0.43f) { dieQuad(it, 0, 1).center },
    XRayNode(XRayRegion.BOARD, true, 0.61f) { it.at(0.12f, 0.42f) },
    XRayNode(XRayRegion.BATTERY, true, 0.79f) { it.at(0.26f, 0.70f) },
    XRayNode(XRayRegion.MODEM, false, 0.07f) { modemRect(it).center },
    XRayNode(XRayRegion.GPU, false, 0.25f) { dieQuad(it, 1, 0).center },
    XRayNode(XRayRegion.MEM, false, 0.43f) { dieQuad(it, 1, 1).center },
    XRayNode(XRayRegion.SKIN, false, 0.61f) { it.at(1f, 0.56f) },
    XRayNode(XRayRegion.CHARGER, false, 0.90f) { chargerRect(it).center },
)

// ── Card ─────────────────────────────────────────────────────────────────────

@Composable
fun ThermalXRayCard(state: StressState, modifier: Modifier = Modifier) {
    val frame = remember(state.thermalSensors, state.batteryTemp, state.thermalHeadroom) {
        XRayMapper.frame(state.thermalSensors, state.batteryTemp, state.thermalHeadroom)
    }

    // Peak-hold: hottest reading per region, cleared whenever a new run starts.
    var peak by remember { mutableStateOf<Map<XRayRegion, XRayReading>>(emptyMap()) }
    var showPeak by remember { mutableStateOf(false) }
    LaunchedEffect(state.isRunning) { if (state.isRunning) peak = emptyMap() }
    LaunchedEffect(frame) { peak = XRayMapper.foldPeak(peak, frame) }

    val shown = if (showPeak && peak.isNotEmpty()) peak else frame.readings

    // Readings glide between 1 Hz samples instead of snapping.
    val values: Map<XRayRegion, Float> = XRayRegion.entries.associateWith { region ->
        animateFloatAsState(
            targetValue = shown[region]?.value ?: region.scaleLoC,
            animationSpec = tween(900),
            label = "xray_${region.name}"
        ).value
    }
    // Ramp position per lit region, in that region's own unit and scale.
    val ramps: Map<XRayRegion, Float> = XRayRegion.entries.associateWith { region ->
        shown[region]?.let { region.rampFor(values.getValue(region), it.unit) } ?: 0f
    }

    // Render budget: no ambient animation under reduced motion or thermal pressure.
    val calm = rememberReducedMotion() || state.currentThermalState >= ThermalState.SERIOUS
    val sweepTransition = rememberInfiniteTransition(label = "xray_sweep")
    val sweep: State<Float>? = if (!calm && !showPeak) {
        sweepTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "xray_sweep_p"
        )
    } else null

    val total = XRayRegion.entries.size
    val live = frame.instrumented
    val statusColor = when {
        live == total -> Forge.phaseMeasured
        live >= total / 2 -> Ramp.at(0.5f)
        else -> Forge.ink1
    }

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
            Column(Modifier.weight(1f)) {
                Text("THERMAL X-RAY", style = ThermoType.title(10f))
                Spacer(Modifier.height(3.dp))
                Text(
                    "$live OF $total REGIONS INSTRUMENTED",
                    style = ThermoType.annotation(7f, statusColor)
                )
            }
            XRayChip("LIVE", selected = !showPeak) { showPeak = false }
            Spacer(Modifier.width(6.dp))
            XRayChip("PEAK", selected = showPeak) { showPeak = true }
        }
        Spacer(Modifier.height(10.dp))

        // ── The body ──────────────────────────────────────────────────────────
        val summary = remember(shown) {
            shown.values.joinToString(", ") {
                if (it.unit == XRayUnit.HEADROOM) "${it.region.label} headroom ${"%.2f".format(it.value)}"
                else "${it.region.label} ${"%.0f".format(it.value)} degrees"
            }
                .ifEmpty { "No thermal sensors readable" }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bodyW = minOf(maxWidth - GUTTER * 2, 172.dp).coerceAtLeast(96.dp)
            val bodyH = bodyW / BODY_ASPECT
            val canvasH = bodyH + BODY_PAD * 2

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(canvasH)
                    .semantics { contentDescription = "Thermal X-ray. $summary" }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = bodyW.toPx()
                    val h = bodyH.toPx()
                    val left = (size.width - w) / 2f
                    val body = Rect(left, BODY_PAD.toPx(), left + w, BODY_PAD.toPx() + h)
                    drawXRay(
                        body = body,
                        ramps = ramps,
                        present = shown.keys,
                        sweep = sweep?.value,
                        gutter = GUTTER
                    )
                }
                NODES.forEach { node ->
                    XRayCallout(
                        node = node,
                        reading = shown[node.region],
                        value = values.getValue(node.region),
                        ramp = ramps.getValue(node.region),
                        modifier = Modifier
                            .align(if (node.left) Alignment.TopStart else Alignment.TopEnd)
                            .offset(y = BODY_PAD + bodyH * node.slot - CALLOUT_H / 2)
                            .width(GUTTER - 6.dp)
                            .height(CALLOUT_H)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        XRayFooter(frame = frame, showPeak = showPeak, onResetPeak = { peak = emptyMap() })
    }
}

// ── Callout ──────────────────────────────────────────────────────────────────

@Composable
private fun XRayCallout(
    node: XRayNode,
    reading: XRayReading?,
    value: Float,
    ramp: Float,
    modifier: Modifier
) {
    val headroom = reading?.unit == XRayUnit.HEADROOM
    Column(
        modifier = modifier,
        // Text hugs the phone side so the leader line meets it.
        horizontalAlignment = if (node.left) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            // Headroom is a different quantity from °C — the label says so.
            if (headroom) "SKIN · HEADROOM" else node.region.label,
            style = ThermoType.label(6.5f, if (reading != null) Forge.ink1 else Forge.ink2),
            maxLines = 1
        )
        if (reading != null) {
            Text(
                if (headroom) "%.2f".format(value) else "%.1f°".format(value),
                style = ThermoType.data(13f, Ramp.at(ramp), FontWeight.Bold),
                maxLines = 1
            )
            Text(
                reading.sensorName.take(16) + if (reading.sensorCount > 1) " ×${reading.sensorCount}" else "",
                style = ThermoType.annotation(6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                "NO SENSOR",
                style = ThermoType.annotation(7f, Forge.ink2.copy(alpha = 0.75f)),
                maxLines = 1
            )
        }
    }
}

// ── Header chip & footer ─────────────────────────────────────────────────────

@Composable
private fun XRayChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Forge.phaseMeasured else Forge.ink2
    Text(
        text,
        style = ThermoType.label(7.5f, tint),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Forge.phaseMeasured.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, tint.copy(alpha = if (selected) 0.4f else 0.25f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun XRayFooter(frame: XRayFrame, showPeak: Boolean, onResetPeak: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // Ramp key — hue is relative to each region's own scale.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("COOL", style = ThermoType.annotation(6.5f))
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(0f, 0.25f, 0.5f, 0.68f, 0.85f, 1f).map { Ramp.at(it) }
                        )
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text("HOT", style = ThermoType.annotation(6.5f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Outline is schematic — part positions are typical, temperatures are measured. " +
                "Hue is scaled to each part's own range. Hatched = no readable sensor.",
            style = ThermoType.annotation(6.5f, Forge.ink2),
        )
        if (frame.instrumented <= 2) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Android restricts most thermal sensors on many devices. Only what this " +
                    "phone lets an app read is lit — nothing is estimated.",
                style = ThermoType.annotation(6.5f, Forge.ink1)
            )
        }
        if (frame.unplaced.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${frame.unplaced.size} more ${if (frame.unplaced.size == 1) "zone" else "zones"} " +
                    "not placed on the outline — see Thermal Horizon.",
                style = ThermoType.annotation(6.5f, Forge.ink2)
            )
        }
        if (showPeak) {
            Spacer(Modifier.height(6.dp))
            Text(
                "PEAK HOLD · TAP TO RESET",
                style = ThermoType.label(7f, Forge.phaseMeasured),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onResetPeak)
                    .padding(vertical = 3.dp)
            )
        }
    }
}

// ── Drawing ──────────────────────────────────────────────────────────────────

private fun DrawScope.drawXRay(
    body: Rect,
    ramps: Map<XRayRegion, Float>,
    present: Set<XRayRegion>,
    sweep: Float?,
    gutter: Dp
) {
    fun tint(r: XRayRegion) = Ramp.at(ramps.getValue(r))

    val bodyRR = RoundRect(body, CornerRadius(body.width * 0.16f))
    val bodyPath = Path().apply { addRoundRect(bodyRR) }

    // Body glass
    drawPath(
        bodyPath,
        Brush.verticalGradient(
            listOf(Forge.raised.copy(alpha = 0.75f), Forge.surface.copy(alpha = 0.95f)),
            startY = body.top, endY = body.bottom
        )
    )

    // Interior, back to front
    regionShape(RoundRect(boardRect(body), CornerRadius(body.width * 0.05f)),
        XRayRegion.BOARD, ramps, present, glow = 0.7f)
    regionShape(RoundRect(batteryRect(body), CornerRadius(body.width * 0.06f)),
        XRayRegion.BATTERY, ramps, present, glow = 0.8f)
    batteryCells(batteryRect(body))
    regionShape(RoundRect(cameraRect(body), CornerRadius(body.width * 0.06f)),
        XRayRegion.CAMERA, ramps, present, glow = 1.0f)
    cameraLenses(cameraRect(body))
    regionShape(RoundRect(modemRect(body), CornerRadius(body.width * 0.03f)),
        XRayRegion.MODEM, ramps, present, glow = 1.0f)
    val port = chargerRect(body)
    regionShape(RoundRect(port, CornerRadius(port.height / 2f)),
        XRayRegion.CHARGER, ramps, present, glow = 1.6f)

    // SoC die — four independently-sensed quadrants
    val quadRadius = CornerRadius(body.width * 0.014f)
    regionShape(RoundRect(dieQuad(body, 0, 0), quadRadius), XRayRegion.CPU, ramps, present, glow = 0.9f)
    regionShape(RoundRect(dieQuad(body, 1, 0), quadRadius), XRayRegion.GPU, ramps, present, glow = 0.9f)
    regionShape(RoundRect(dieQuad(body, 0, 1), quadRadius), XRayRegion.NPU, ramps, present, glow = 0.9f)
    regionShape(RoundRect(dieQuad(body, 1, 1), quadRadius), XRayRegion.MEM, ramps, present, glow = 0.9f)

    // Skin = the rim. Lit by the skin sensor, or dashed when there is none.
    if (XRayRegion.SKIN in present) {
        val t = ramps.getValue(XRayRegion.SKIN)
        val c = tint(XRayRegion.SKIN)
        val strength = 0.6f + 0.8f * t
        drawPath(bodyPath, c.copy(alpha = (0.05f * strength).coerceAtMost(1f)), style = Stroke(16.dp.toPx()))
        drawPath(bodyPath, c.copy(alpha = (0.09f * strength).coerceAtMost(1f)), style = Stroke(10.dp.toPx()))
        drawPath(bodyPath, c.copy(alpha = (0.18f * strength).coerceAtMost(1f)), style = Stroke(5.dp.toPx()))
        drawPath(bodyPath, c, style = Stroke(1.6.dp.toPx()))
    } else {
        drawPath(
            bodyPath, Forge.ink2.copy(alpha = 0.6f),
            style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        )
    }

    // Leader lines + anchor dots
    val gutterPx = gutter.toPx()
    val gap = 3.dp.toPx()
    NODES.forEach { node ->
        val lit = node.region in present
        val c = if (lit) tint(node.region) else Forge.ink2
        val a = node.anchor(body)
        val y = body.top + node.slot * body.height
        val startX = if (node.left) gutterPx - 6.dp.toPx() + gap else size.width - gutterPx + 6.dp.toPx() - gap
        drawLine(c.copy(alpha = if (lit) 0.55f else 0.3f), Offset(startX, y), a, 0.8.dp.toPx())
        if (lit) drawCircle(c, 2.4.dp.toPx(), a)
        else drawCircle(c.copy(alpha = 0.6f), 2.4.dp.toPx(), a, style = Stroke(1.dp.toPx()))
    }

    // Scan line — clipped to the body
    if (sweep != null) {
        val y = body.top + sweep * body.height
        val band = body.height * 0.14f
        clipPath(bodyPath) {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Forge.phaseMeasured.copy(alpha = 0.10f),
                        Forge.phaseMeasured.copy(alpha = 0.32f)),
                    startY = y - band, endY = y
                ),
                topLeft = Offset(body.left, y - band),
                size = Size(body.width, band)
            )
            drawLine(Forge.phaseMeasured.copy(alpha = 0.7f), Offset(body.left, y), Offset(body.right, y), 1.dp.toPx())
        }
    }
}

/** One part of the phone: heat-lit when its sensor exists, hatched and dashed when not. */
private fun DrawScope.regionShape(
    rr: RoundRect,
    region: XRayRegion,
    ramps: Map<XRayRegion, Float>,
    present: Set<XRayRegion>,
    glow: Float
) {
    val path = Path().apply { addRoundRect(rr) }
    if (region in present) {
        val t = ramps.getValue(region)
        val c = Ramp.at(t)
        drawPath(path, c.copy(alpha = 0.14f + 0.16f * t))
        clipPath(path) {
            bloom(Offset((rr.left + rr.right) / 2f, (rr.top + rr.bottom) / 2f),
                max(rr.width, rr.height) * glow, c, 0.45f + 0.4f * t)
        }
        drawPath(path, c.copy(alpha = 0.85f), style = Stroke(1.1.dp.toPx()))
    } else {
        drawPath(path, Forge.ink2.copy(alpha = 0.05f))
        clipPath(path) {
            val step = 6.dp.toPx()
            var x = rr.left - rr.height
            while (x < rr.right) {
                drawLine(Forge.ink2.copy(alpha = 0.22f), Offset(x, rr.bottom), Offset(x + rr.height, rr.top), 0.8.dp.toPx())
                x += step
            }
        }
        drawPath(
            path, Forge.ink2.copy(alpha = 0.45f),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        )
    }
}

private fun DrawScope.batteryCells(r: Rect) {
    val c = Forge.ink2.copy(alpha = 0.35f)
    listOf(1f / 3f, 2f / 3f).forEach { f ->
        val x = r.left + r.width * f
        drawLine(c, Offset(x, r.top + 6.dp.toPx()), Offset(x, r.bottom - 6.dp.toPx()), 0.8.dp.toPx())
    }
}

private fun DrawScope.cameraLenses(r: Rect) {
    val rad = r.height * 0.27f
    listOf(0.24f, 0.5f, 0.76f).forEach { f ->
        drawCircle(Forge.ink1.copy(alpha = 0.35f), rad, Offset(r.left + r.width * f, r.center.y),
            style = Stroke(1.dp.toPx()))
    }
}
