package com.example.thermohammer.ui.overlays

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.BenchmarkHistoryStore
import com.example.thermohammer.data.BenchmarkRecord
import com.example.thermohammer.data.MyRuns
import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.network.BenchmarkUpload
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MY RUNS: every benchmark run kept on this phone, newest first, with the score over time, each run's six
 * sections, and a submit for a verified run that hasn't been sent. Reads and writes the history itself, so the
 * home screen and the leaderboard open it the same way; [onChanged] tells the caller a run was submitted.
 */
@Composable
fun MyRunsOverlay(isNetworkConnected: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { BenchmarkHistoryStore(context) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<MyRuns.History?>(null) }
    var open by remember { mutableStateOf<String?>(null) }          // the run whose sections are showing
    var submittingId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<Pair<String, String>?>(null) }   // run id → what its submit answered

    suspend fun load() {
        history = withContext(Dispatchers.IO) { MyRuns.build(store.all(), BenchmarkRecord.thisDeviceModel()) }
    }
    LaunchedEffect(Unit) { load() }

    fun submit(record: BenchmarkRecord) {
        submittingId = record.id; note = null
        scope.launch {
            note = try {
                val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
                val res = BenchmarkUpload.submit(context, record, soc)
                withContext(Dispatchers.IO) { store.markSubmitted(record.id, res.id) }
                load(); onChanged()
                record.id to "Submitted · rank #${res.rank}"
            } catch (e: Exception) {
                record.id to "Submit failed: ${e.message ?: "no answer from the server"}"
            }
            submittingId = null
        }
    }

    Box(Modifier.fillMaxSize().background(Forge.bg.copy(alpha = 0.85f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Forge.raised)
                .border(1.dp, Forge.ink0.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MY RUNS", style = TextStyle(color = Forge.ink0, fontSize = 16.sp, fontFamily = InstrumentMono,
                        fontWeight = FontWeight.Black, letterSpacing = 1.sp))
                    Text("Every benchmark on this phone", style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f),
                        fontSize = 10.sp, fontFamily = InstrumentMono))
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Forge.ink0.copy(alpha = 0.08f)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Forge.ink0, fontSize = 12.sp, fontWeight = FontWeight.Black) }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Forge.ink0.copy(alpha = 0.1f))

            val h = history
            when {
                h == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Forge.phasePreFlight, strokeWidth = 3.dp)
                }
                h.rows.isEmpty() -> Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No benchmark runs yet", style = ThermoType.data(13f, Forge.ink0, FontWeight.Bold))
                    Spacer(Modifier.height(6.dp))
                    Text("Run the benchmark and every result is kept here, so you can see how your phone does over time.",
                        style = ThermoType.annotation(10f, Forge.ink1), textAlign = TextAlign.Center)
                    for (line in MyRuns.footnotes(h).dropLast(1)) {
                        Spacer(Modifier.height(10.dp))
                        Text(line, style = ThermoType.annotation(9f, Forge.ink2), textAlign = TextAlign.Center)
                    }
                }
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Summary(h) }
                    if (h.rows.size >= 2) item { ScoreChart(h) }
                    item {
                        Text("TAP A RUN FOR ITS SECTIONS", style = TextStyle(color = Forge.ink0.copy(alpha = 0.3f), fontSize = 8.sp,
                            fontFamily = InstrumentMono), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                    items(h.rows, key = { it.record.id }) { row ->
                        RunRow(
                            row = row,
                            isOpen = open == row.record.id,
                            onToggle = { open = if (open == row.record.id) null else row.record.id },
                            isNetworkConnected = isNetworkConnected,
                            isSubmitting = submittingId == row.record.id,
                            note = note?.takeIf { it.first == row.record.id }?.second,
                            onSubmit = { submit(row.record) }
                        )
                    }
                    item {
                        Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (line in MyRuns.footnotes(h)) Text(line, style = ThermoType.annotation(9f, Forge.ink2))
                        }
                    }
                }
            }
        }
    }
}

private fun dateTime(millis: Long): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.ENGLISH).format(Date(millis))

/** BEST · LATEST · RUNS */
@Composable
private fun Summary(h: MyRuns.History) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Forge.ink0.copy(alpha = 0.03f)).padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        h.best?.let { SummaryCell("BEST", "%,d".format(it.score), ShareLinks.date(it.timestamp), Forge.phaseMeasured) }
        h.latest?.let { SummaryCell("LATEST", "%,d".format(it.score), ShareLinks.date(it.timestamp), Forge.ink0) }
        SummaryCell("RUNS", "${h.rows.size}", "${h.verified} verified", Forge.ink0)
    }
}

@Composable
private fun RowScope.SummaryCell(label: String, value: String, sub: String, color: Color) {
    Column(Modifier.weight(1f)) {
        Text(label, style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black))
        Text(value, style = TextStyle(color = color, fontSize = 17.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black), maxLines = 1)
        Text(sub, style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono), maxLines = 1)
    }
}

/** Total score per run, oldest to newest, evenly spaced: filled dots are verified runs, hollow ones unverified, the ring the best. */
@Composable
private fun ScoreChart(h: MyRuns.History) {
    val runs = h.chronological
    val scores = runs.map { it.record.score }
    val hi = scores.max()
    val lo = scores.min()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Forge.ink0.copy(alpha = 0.03f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row {
            Text("SCORE OVER TIME", modifier = Modifier.weight(1f), style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f),
                fontSize = 8.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black))
            Text("%,d – %,d".format(lo, hi), style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono))
        }
        val line = Forge.phaseMeasured
        val hollow = Forge.ink2
        val fill = Forge.raised
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val pad = 8.dp.toPx()
            // a flat history sits mid-height; otherwise the lowest run near the bottom, the highest near the top
            val span = (hi - lo).toFloat()
            fun y(score: Int): Float = if (span <= 0f) size.height / 2 else pad + (size.height - 2 * pad) * (1f - (score - lo) / span)
            fun x(i: Int): Float = pad + (size.width - 2 * pad) * (i.toFloat() / (runs.size - 1))
            for (g in 0..2) {
                val gy = pad + (size.height - 2 * pad) * g / 2f
                drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx())
            }
            val path = Path()
            runs.forEachIndexed { i, r -> if (i == 0) path.moveTo(x(i), y(r.record.score)) else path.lineTo(x(i), y(r.record.score)) }
            drawPath(path, line.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val dot = 3.5.dp.toPx()
            runs.forEachIndexed { i, r ->
                val c = Offset(x(i), y(r.record.score))
                if (r.isBest) drawCircle(line, dot * 2, c, style = Stroke(1.5.dp.toPx()))
                if (r.record.validityFlags == 0) drawCircle(line, dot, c)
                else { drawCircle(fill, dot, c); drawCircle(hollow, dot, c, style = Stroke(1.5.dp.toPx())) }
            }
        }
        Row {
            Text(ShareLinks.date(runs.first().record.timestamp), modifier = Modifier.weight(1f),
                style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono))
            Text(ShareLinks.date(runs.last().record.timestamp),
                style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono))
        }
        Text("● verified   ○ unverified   ◎ best", style = TextStyle(color = Forge.ink0.copy(alpha = 0.3f), fontSize = 8.sp, fontFamily = InstrumentMono))
    }
}

@Composable
private fun RunRow(
    row: MyRuns.Row,
    isOpen: Boolean,
    onToggle: () -> Unit,
    isNetworkConnected: Boolean,
    isSubmitting: Boolean,
    note: String?,
    onSubmit: () -> Unit
) {
    val r = row.record
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.surface)
            .border(1.dp, if (row.isBest) Forge.phaseMeasured.copy(alpha = 0.6f) else Forge.ink0.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%,d".format(r.score), style = TextStyle(color = Forge.ink0, fontSize = 15.sp, fontFamily = InstrumentMono,
                        fontWeight = FontWeight.Black))
                    if (row.isBest) Text("  BEST", style = TextStyle(color = Forge.phaseMeasured, fontSize = 8.sp,
                        fontFamily = InstrumentMono, fontWeight = FontWeight.Black), modifier = Modifier.padding(bottom = 2.dp))
                }
                Text(listOfNotNull(dateTime(r.timestamp), r.osVersion?.let { "Android $it" }).joinToString(" · "),
                    style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono), maxLines = 1)
            }
            row.changePct?.let { pct ->
                Text(MyRuns.changeText(pct), modifier = Modifier.padding(horizontal = 8.dp),
                    style = TextStyle(color = when {
                        pct >= 0.05 -> Forge.phaseMeasured
                        pct <= -0.05 -> Ramp.at(0.85f)
                        else -> Forge.ink2
                    }, fontSize = 10.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Bold))
            }
            StatusChip(row.status)
        }
        if (isOpen) {
            Spacer(Modifier.height(10.dp))
            Sections(r)
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(r.osVersion?.let { "Android $it" }, r.osBuild, r.gpuName).joinToString(" · "),
                style = TextStyle(color = Forge.ink0.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = InstrumentMono))
            if (r.validityFlags != 0) {
                Spacer(Modifier.height(6.dp))
                Text(validityNote(r.validityFlags) + " Only verified runs can go to the leaderboard.",
                    style = ThermoType.annotation(9f, Ramp.at(0.5f)))
            }
            // the run's full report, kept when it finished (BenchmarkReportStore): runs from before it was kept have none
            val context = androidx.compose.ui.platform.LocalContext.current
            val report = remember(r.id) { com.example.thermohammer.data.BenchmarkReportStore(context).load(r.id) }
            if (report != null) {
                Spacer(Modifier.height(10.dp))
                ActionPill("COPY REPORT", Forge.ink1) {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ThermoHammer benchmark report", report))
                    android.widget.Toast.makeText(context, "Report copied — paste it anywhere", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            when {
                row.canSubmit && isSubmitting -> {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), color = Forge.phasePreFlight, strokeWidth = 2.dp)
                }
                row.canSubmit && isNetworkConnected -> {
                    Spacer(Modifier.height(10.dp))
                    ActionPill("SUBMIT ↑", Forge.phasePreFlight, onSubmit)
                }
                row.canSubmit -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Not on the leaderboard yet. Turn on the internet to submit it.", style = ThermoType.annotation(9f, Ramp.at(0.5f)))
                }
                r.submitted && r.serverId != null -> {
                    Spacer(Modifier.height(10.dp))
                    ActionPill("↗ OPEN ITS PAGE", Forge.phaseMeasured) { runCatching { uriHandler.openUri(ShareLinks.runUrl(r.serverId)) } }
                }
            }
            note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = ThermoType.annotation(9f, Forge.ink1))
            }
        }
    }
}

@Composable
private fun StatusChip(status: MyRuns.Status) {
    val (label, color) = when (status) {
        MyRuns.Status.SUBMITTED -> "SUBMITTED" to Forge.phaseMeasured
        MyRuns.Status.NOT_SUBMITTED -> "NOT SUBMITTED" to Ramp.at(0.5f)
        MyRuns.Status.UNVERIFIED -> "UNVERIFIED" to Forge.ink2
    }
    Text(label, style = TextStyle(color = color, fontSize = 7.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 4.dp))
}

/** The six section scores, two rows of three, each in its section's colour. */
@Composable
private fun Sections(r: BenchmarkRecord) {
    val cells = listOf(
        Triple("CPU", r.cpuScore, cpuColor()), Triple("MEM", r.memScore, memColor()), Triple("GPU", r.gpuScore, benchmarkColor(null)),
        Triple("UX", r.uxScore, uxColor(null)), Triple("AI", r.aiScore, aiColor()), Triple("LLM", r.llmScore, llmColor())
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (line in cells.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, score, color) in line) Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(label, style = TextStyle(color = color, fontSize = 8.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black))
                Text("%,d".format(score), style = TextStyle(color = Forge.ink0, fontSize = 12.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black))
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, color: Color, onClick: () -> Unit) {
    Text(label, textAlign = TextAlign.Center, maxLines = 1,
        style = TextStyle(color = color, fontSize = 9.sp, fontFamily = InstrumentMono, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 9.dp))
}
