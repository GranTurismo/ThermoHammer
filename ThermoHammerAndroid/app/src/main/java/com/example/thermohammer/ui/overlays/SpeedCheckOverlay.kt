package com.example.thermohammer.ui.overlays

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.SpeedCheckSession
import com.example.thermohammer.data.SpeedCheckStore
import com.example.thermohammer.engine.SpeedCheck
import com.example.thermohammer.engine.SpeedCheckRunner
import com.example.thermohammer.engine.SpeedMeasurement
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// FIND MY LOST SPEED
//
//   intro → a 30-second check (live work/s and a sparkline) → what is holding
//   the phone back, with the way to its setting → "I changed it — check again"
//   → the two checks side by side. The first check is saved, so the second
//   still works after a trip to Settings or an app restart (for an hour).
// ─────────────────────────────────────────────────────────────────────────────

private enum class SpeedPhase { INTRO, RUNNING, DIAGNOSIS, COMPARISON }

/** [busy]: a benchmark or stress run is active — no check then. [health]: the last benchmark's Phone Health, if at hand. */
@Composable
fun SpeedCheckOverlay(busy: Boolean, health: SpeedCheck.HealthNote?, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SpeedCheckStore(context) }
    val runner = remember { SpeedCheckRunner(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val saved = remember { store.load() }
    var first by remember { mutableStateOf(saved?.first) }
    var second by remember { mutableStateOf(saved?.second) }
    var phase by remember {
        mutableStateOf(when { saved?.second != null -> SpeedPhase.COMPARISON; saved != null -> SpeedPhase.DIAGNOSIS; else -> SpeedPhase.INTRO })
    }
    var checkingAgain by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var rateNow by remember { mutableDoubleStateOf(0.0) }
    var windows by remember { mutableStateOf<List<Double>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start(again: Boolean) {
        if (busy || job?.isActive == true) return
        // a first check over an hour old is no baseline any more: this one starts over
        val second2 = again && first?.let { !SpeedCheck.isExpired(it, System.currentTimeMillis()) } == true
        val back = phase
        error = null; progress = 0f; rateNow = 0.0; windows = emptyList(); checkingAgain = second2
        phase = SpeedPhase.RUNNING
        job = scope.launch {
            try {
                val m = runner.measure(onSecond = { windows = it }, onTick = { f, r -> progress = f; rateNow = r })
                val base = first
                if (second2 && base != null) {
                    second = m; store.save(SpeedCheckSession(base, m)); phase = SpeedPhase.COMPARISON
                } else {
                    first = m; second = null; store.save(SpeedCheckSession(m)); phase = SpeedPhase.DIAGNOSIS
                }
            } catch (e: CancellationException) {
                phase = back
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "The check failed"
                phase = back
            }
        }
    }

    // the screen stays on while a check runs: 30 s can outlast the phone's screen timeout
    val view = LocalView.current
    DisposableEffect(phase == SpeedPhase.RUNNING) {
        val running = phase == SpeedPhase.RUNNING
        if (running) view.keepScreenOn = true
        onDispose { if (running) view.keepScreenOn = false }
    }

    OverlayContainer {
        OverlayCard(width = 340) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel("THERMOHAMMER", size = 9f)
                Spacer(Modifier.height(6.dp))
                MonoTitle("FIND MY LOST SPEED", size = 14f)
                Spacer(Modifier.height(16.dp))
                when (phase) {
                    SpeedPhase.INTRO -> Intro(busy, error, onStart = { start(false) }, onClose = onClose)
                    SpeedPhase.RUNNING -> Running(checkingAgain, progress, rateNow, windows, onStop = { job?.cancel() })
                    SpeedPhase.DIAGNOSIS -> first?.let { m ->
                        Diagnosis(m, SpeedCheck.diagnose(m, android.os.Build.MANUFACTURER, health), busy, error,
                            onOpenSettings = { openBatterySettings(context) },
                            onCheckAgain = { start(true) },
                            onStartOver = { if (!busy) { store.clear(); first = null; second = null; start(false) } },
                            onDone = onClose)
                    }
                    SpeedPhase.COMPARISON -> {
                        val a = first
                        val b = second
                        if (a != null && b != null) Comparison(a, b, onDone = { store.clear(); onClose() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro(busy: Boolean, error: String?, onStart: () -> Unit, onClose: () -> Unit) {
    Text("A 30-second check of how fast your phone really runs, then what's slowing it down.",
        style = ThermoType.data(11f, Forge.ink1), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text("Every core works flat out for 30 seconds — keep the app open.", style = ThermoType.annotation(8f, Forge.ink2),
        textAlign = TextAlign.Center)
    Notices(busy, error)
    Spacer(Modifier.height(18.dp))
    CheckButton("START 30-SECOND CHECK", enabled = !busy, onClick = onStart)
    Spacer(Modifier.height(8.dp))
    SecondaryButton("CLOSE", onClose)
}

@Composable
private fun Running(again: Boolean, progress: Float, rateNow: Double, windows: List<Double>, onStop: () -> Unit) {
    Text(if (again) "CHECKING AGAIN" else "CHECKING", style = ThermoType.label(9f, Forge.phaseMeasured))
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Forge.ink0.copy(alpha = 0.07f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.01f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp))
            .background(Forge.phaseMeasured))
    }
    Spacer(Modifier.height(6.dp))
    Text("SECOND %d / %d".format((progress * SpeedCheck.SECONDS).toInt().coerceAtMost(SpeedCheck.SECONDS), SpeedCheck.SECONDS),
        style = ThermoType.annotation(8f, Forge.ink2))
    Spacer(Modifier.height(14.dp))
    Text(if (rateNow > 0) "%,.0f".format(rateNow) else "—", style = ThermoType.hero(46f))
    Text("WORK UNITS / S", style = ThermoType.label(8f))
    Spacer(Modifier.height(12.dp))
    Sparkline(windows, Modifier.fillMaxWidth().height(70.dp))
    Spacer(Modifier.height(8.dp))
    Text("Burst: seconds 1–5. Sustained: seconds 11–30.", style = ThermoType.annotation(8f, Forge.ink2))
    Spacer(Modifier.height(16.dp))
    SecondaryButton("STOP", onStop)
}

@Composable
private fun Diagnosis(m: SpeedMeasurement, d: SpeedCheck.Diagnosis, busy: Boolean, error: String?, onOpenSettings: () -> Unit,
                      onCheckAgain: () -> Unit, onStartOver: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("BURST /S", "%,.0f".format(m.burst), Forge.ink0)
        Stat("SUSTAINED /S", "%,.0f".format(m.sustained), Forge.ink0)
        Stat("KEPT", "${SpeedCheck.kept(m)}%", Ramp.forCapacity(SpeedCheck.kept(m).toFloat()))
    }
    Spacer(Modifier.height(10.dp))
    Sparkline(m.perSecond, Modifier.fillMaxWidth().height(54.dp))
    Spacer(Modifier.height(14.dp))
    for (c in d.causes) {
        CauseCard(c)
        Spacer(Modifier.height(8.dp))
    }
    d.note?.let {
        Text(it, style = ThermoType.annotation(9f, Forge.ink1), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
    Notices(busy, error)
    Spacer(Modifier.height(12.dp))
    if (d.causes.all { it.kind == SpeedCheck.Kind.NO_LOST_SPEED }) {
        // nothing is holding it back: no setting to change — DONE leads, CHECK AGAIN is a fresh first check
        PrimaryButton("DONE", onDone, accent = Forge.phaseMeasured)
        Spacer(Modifier.height(8.dp))
        SecondaryButton("CHECK AGAIN", onStartOver)
    } else {
        SecondaryButton("OPEN BATTERY SETTINGS", onOpenSettings)
        Spacer(Modifier.height(8.dp))
        CheckButton("I CHANGED IT — CHECK AGAIN", enabled = !busy, onClick = onCheckAgain)
        Spacer(Modifier.height(8.dp))
        SecondaryButton("DONE", onDone)
        Spacer(Modifier.height(8.dp))
        Text("START OVER", style = ThermoType.label(8f, Forge.ink2),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !busy, onClick = onStartOver)
                .padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun Comparison(a: SpeedMeasurement, b: SpeedMeasurement, onDone: () -> Unit) {
    val cmp = SpeedCheck.compare(a, b)
    val color = when (cmp.change) {
        SpeedCheck.Change.FASTER -> Forge.phaseMeasured
        SpeedCheck.Change.SLOWER -> Ramp.at(0.68f)
        SpeedCheck.Change.NO_CHANGE -> Forge.ink1
    }
    Text(cmp.title(), style = ThermoType.hero(38f).copy(color = color), textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(cmp.text, style = ThermoType.data(11f, Forge.ink0), textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    val top = maxOf(a.burst, a.sustained, b.burst, b.sustained, 1.0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RunBars("FIRST CHECK", a, top, Forge.ink2, Modifier.weight(1f))
        RunBars("SECOND CHECK", b, top, color, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Text("Work units per second. Burst: seconds 1–5 · sustained: seconds 11–30.", style = ThermoType.annotation(8f, Forge.ink2),
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    PrimaryButton("DONE", onDone, accent = Forge.phaseMeasured)
}

/** One check's burst and sustained as two bars on a scale shared with the other check. */
@Composable
private fun RunBars(title: String, m: SpeedMeasurement, top: Double, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Forge.surface).border(1.dp, Forge.hairline, RoundedCornerShape(12.dp))
        .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = ThermoType.label(8f))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, v) in listOf("BURST" to m.burst, "SUST." to m.sustained)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.fillMaxHeight()) {
                    Text("%,.0f".format(v), style = ThermoType.data(9f, Forge.ink0, FontWeight.Bold), maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(26.dp).fillMaxHeight((v / top).toFloat().coerceIn(0.02f, 0.82f))
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                        .background(if (label == "BURST") accent.copy(alpha = 0.45f) else accent))
                    Spacer(Modifier.height(3.dp))
                    Text(label, style = ThermoType.annotation(7f, Forge.ink2), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CauseCard(c: SpeedCheck.Cause) {
    val accent = when (c.kind) {
        SpeedCheck.Kind.POWER_SAVING, SpeedCheck.Kind.HELD_BACK_WHILE_COOL -> Ramp.at(0.5f)
        SpeedCheck.Kind.HEAT -> Ramp.at(0.68f)
        SpeedCheck.Kind.NO_LOST_SPEED -> Forge.phaseMeasured
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Forge.surface)
        .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(c.title, style = ThermoType.title(10f, accent))
        Spacer(Modifier.height(4.dp))
        Text(c.text, style = ThermoType.data(10f, Forge.ink0))
        for (step in c.steps) {
            Spacer(Modifier.height(6.dp))
            Row {
                Text("› ", style = ThermoType.annotation(9f, accent))
                Text(step, style = ThermoType.annotation(9f, Forge.ink1))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(value, style = ThermoType.data(15f, color, FontWeight.Black))
        Text(label, style = ThermoType.annotation(7f, Forge.ink2))
    }
}

@Composable
private fun Notices(busy: Boolean, error: String?) {
    if (busy) {
        Spacer(Modifier.height(10.dp))
        Text("A benchmark or stress run is active — the check can start when it has finished.",
            style = ThermoType.annotation(9f, Ramp.at(0.5f)), textAlign = TextAlign.Center)
    }
    if (error != null) {
        Spacer(Modifier.height(10.dp))
        Text("⚠ $error", style = ThermoType.annotation(9f, Ramp.at(0.85f)), textAlign = TextAlign.Center)
    }
}

@Composable
private fun CheckButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forge.phaseMeasured, disabledContainerColor = Forge.ink0.copy(alpha = 0.12f))
    ) {
        Text(text, style = TextStyle(color = if (enabled) Forge.bg else Forge.ink0.copy(alpha = 0.4f), fontSize = 12.sp,
            fontFamily = InstrumentMono, fontWeight = FontWeight.Bold), modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Work units per second, one point a second, from 0 up — the check's shape at a glance. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Forge.surface)) {
        if (values.size < 2) return@Canvas
        val top = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.1
        val stepX = size.width / (SpeedCheck.SECONDS - 1)
        fun pt(i: Int) = Offset(i * stepX, (size.height - (values[i] / top).toFloat() * size.height))
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y) }
        val area = Path().apply { addPath(line); lineTo(pt(values.size - 1).x, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(Forge.phaseMeasured.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(line, Forge.phaseMeasured, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
    }
}

/**
 * Where the power settings are: the battery saver page while saver is on, else the battery page, else Settings —
 * whichever this phone has.
 */
fun openBatterySettings(context: Context) {
    val saverOn = try { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode } catch (_: Exception) { false }
    val pages = listOfNotNull(
        if (saverOn) Settings.ACTION_BATTERY_SAVER_SETTINGS else null,
        Intent.ACTION_POWER_USAGE_SUMMARY,
        Settings.ACTION_SETTINGS
    )
    for (action in pages) {
        try {
            context.startActivity(Intent(action).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
