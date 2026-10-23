package com.example.thermohammer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.thermohammer.data.RunRecord
import com.example.thermohammer.data.UpdateImpact
import com.example.thermohammer.data.UpdateImpact.Insight
import com.example.thermohammer.data.UpdateImpact.Verdict
import com.example.thermohammer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// UPDATE IMPACT
//
//   The phone compared with ITSELF across an OS update. Two states:
//     PROMPT — the OS changed and there is no usable test since: offer to repeat the old one
//     RESULT — before → after, medians of like-for-like clean runs, with a verdict that
//              only appears when the change is bigger than normal run-to-run noise
//   Wording is deliberately temporal ("after update"), never causal.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun UpdateImpactCard(
    insight: Insight,
    onRetest: (RunRecord) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (insight) {
        is Insight.Prompt -> PromptCard(insight, onRetest, onDismiss, modifier)
        is Insight.Result -> ResultCard(insight, onRetest, onDismiss, modifier)
    }
}

// ── Prompt ───────────────────────────────────────────────────────────────────

@Composable
private fun PromptCard(
    p: Insight.Prompt,
    onRetest: (RunRecord) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier
) {
    val accent = Forge.phasePreFlight
    CardShell(accent, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            StatusLED(accent)
            Spacer(Modifier.width(8.dp))
            Text("UPDATE DETECTED", style = ThermoType.title(10f, accent), modifier = Modifier.weight(1f))
            Text(
                "NOT NOW",
                style = ThermoType.label(7.5f, Forge.ink2),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OsColumn("WAS", osName(p.last.osVersion), osDetail(p.last.securityPatch, p.last.osBuild),
                Modifier.weight(1f))
            Text("→", style = ThermoType.data(16f, Forge.ink2))
            OsColumn("NOW", osName(p.nowOsVersion), osDetail(p.nowPatch, p.nowOsBuild),
                Modifier.weight(1f), alignEnd = true)
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "Your last test: %.1f%% · %s · %s".format(p.last.capacity, settingsLabel(p.last), dateLabel(p.last.timestamp)),
            style = ThermoType.data(9.5f, Forge.ink0)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Run the same test again to see whether this update changed how your phone holds up under load.",
            style = ThermoType.annotation(8f, Forge.ink1)
        )
        Spacer(Modifier.height(12.dp))
        ActionButton("RUN SAME TEST", accent) { onRetest(p.last) }
    }
}

// ── Result ───────────────────────────────────────────────────────────────────

@Composable
private fun ResultCard(
    r: Insight.Result,
    onRetest: (RunRecord) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier
) {
    val tint = verdictColor(r.verdict)
    CardShell(tint, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("UPDATE IMPACT", style = ThermoType.title(10f), modifier = Modifier.weight(1f))
            Text(
                "✕",
                style = ThermoType.data(11f, Forge.ink2),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SideColumn("BEFORE", r.before, Modifier.weight(1f))
            Text("→", style = ThermoType.data(16f, Forge.ink2))
            SideColumn("AFTER", r.after, Modifier.weight(1f), alignEnd = true)
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(signed(r.deltaPts) + " pts", style = ThermoType.hero(30f).copy(color = tint))
            Spacer(Modifier.width(10.dp))
            Text(
                verdictText(r.verdict),
                style = ThermoType.label(8.5f, tint),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        Text(
            when (r.verdict) {
                Verdict.NO_CLEAR_CHANGE ->
                    "Within normal run-to-run variation (±%.0f pts). Nothing here shows the update changed your phone."
                        .format(r.thresholdPts)
                else ->
                    "Bigger than normal run-to-run variation (±%.0f pts) for this phone and test.".format(r.thresholdPts)
            },
            style = ThermoType.annotation(8f, Forge.ink1)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Same phone, same settings, clean runs only. This shows what changed after the update — " +
                "not what caused it. Room temperature, case and battery level still matter.",
            style = ThermoType.annotation(7f, Forge.ink2)
        )
        if (r.weak) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Based on ${runs(r.before.runs.size)} before and ${runs(r.after.runs.size)} after — " +
                    "run again to confirm.",
                style = ThermoType.annotation(8f, Ramp.at(0.5f))
            )
            Spacer(Modifier.height(10.dp))
            ActionButton("RUN AGAIN TO CONFIRM", tint) { onRetest(r.before.latest) }
        }
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────

@Composable
private fun CardShell(accent: Color, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Forge.surface)
            .cornerTicks(accent.copy(alpha = 0.5f))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun OsColumn(tag: String, name: String, detail: String, modifier: Modifier, alignEnd: Boolean = false) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(tag, style = ThermoType.label(7f))
        Spacer(Modifier.height(2.dp))
        Text(name, style = ThermoType.data(12f, Forge.ink0, FontWeight.Bold))
        if (detail.isNotEmpty()) Text(detail, style = ThermoType.annotation(7f, Forge.ink1))
    }
}

@Composable
private fun SideColumn(tag: String, side: UpdateImpact.Side, modifier: Modifier, alignEnd: Boolean = false) {
    val r = side.latest
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(tag, style = ThermoType.label(7f))
        Spacer(Modifier.height(2.dp))
        Text("%.1f%%".format(side.median), style = ThermoType.data(20f, Ramp.forCapacity(side.median.toFloat()), FontWeight.Bold))
        Text(osName(r.osVersion), style = ThermoType.data(9f, Forge.ink0, FontWeight.Bold))
        Text(osDetail(r.securityPatch, r.osBuild), style = ThermoType.annotation(6.5f, Forge.ink1))
        Text(
            if (side.runs.size == 1) "1 run" else "median of ${side.runs.size} runs",
            style = ThermoType.annotation(6.5f, Forge.ink2)
        )
    }
}

@Composable
private fun ActionButton(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text,
        style = ThermoType.title(9f, accent),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

// ── Formatting ───────────────────────────────────────────────────────────────

private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.SLOWER -> Ramp.at(0.68f)
    Verdict.FASTER -> Forge.phaseMeasured
    Verdict.NO_CLEAR_CHANGE -> Forge.ink1
}

private fun verdictText(v: Verdict) = when (v) {
    Verdict.SLOWER -> "LOWER AFTER UPDATE"
    Verdict.FASTER -> "HIGHER AFTER UPDATE"
    Verdict.NO_CLEAR_CHANGE -> "NO CLEAR CHANGE"
}

/** Proper minus sign; an exact zero stays unsigned. */
private fun signed(v: Double): String = when {
    v > 0.05 -> "+%.1f".format(v)
    v < -0.05 -> "−%.1f".format(-v)
    else -> "0.0"
}

private fun runs(n: Int) = if (n == 1) "1 run" else "$n runs"

private fun osName(version: String) = "ANDROID $version"

/** Patch level and exact build id, one per line — long build ids must not crowd a narrow column. */
private fun osDetail(patch: String?, build: String?): String =
    listOfNotNull(patch?.takeIf { it.isNotBlank() }?.let { "PATCH $it" }, build?.takeIf { it.isNotBlank() })
        .joinToString("\n")

private fun settingsLabel(r: RunRecord): String {
    val dur = when (r.durationType) { 0 -> "5 MIN"; 1 -> "15 MIN"; 2 -> "30 MIN"; else -> "?" }
    val mode = when (r.stressMode) { 1 -> "GPU 3D"; 2 -> "CPU + GPU"; else -> null }
    val threads = if (r.stressMode == 1) null else if (r.threadingType == 0) "1 THREAD" else "MULTI THREAD"
    return listOfNotNull(dur, mode, threads).joinToString(" · ")
}

private fun dateLabel(ts: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts)).uppercase(Locale.getDefault())
