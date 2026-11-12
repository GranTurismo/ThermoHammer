package com.example.thermohammer.ui.overlays

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.BenchmarkComparison
import com.example.thermohammer.data.ComparedGroup
import com.example.thermohammer.data.ComparedRow
import com.example.thermohammer.data.ComparedSection
import com.example.thermohammer.data.Duel
import com.example.thermohammer.data.Lead
import com.example.thermohammer.data.advantageText
import com.example.thermohammer.data.deviceDisplayName
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.BenchmarkDetailDto
import com.example.thermohammer.network.BenchmarkDto
import com.example.thermohammer.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The two sides of a comparison: cyan and amber, the pair the Windows app's run comparison uses too. */
val CompareA = Color(0xFF00E6FF)
val CompareB = Color(0xFFFFAB00)

private fun leadColor(lead: Lead): Color = when (lead) { Lead.A -> CompareA; Lead.B -> CompareB; else -> Forge.ink1 }

private fun sectionColor(key: String): Color = when (key) {
    "cpu" -> cpuColor()
    "mem" -> memColor()
    "gpu" -> Forge.phaseCalibration
    "ux" -> Forge.phaseMeasured
    "ai" -> aiColor()
    "llm" -> llmColor()
    else -> Forge.ink1
}

private fun shortTitle(s: ComparedSection): String = when (s.key) {
    "cpu" -> "CPU"; "mem" -> "MEM"; "gpu" -> "GPU"; "ux" -> "UX"; "ai" -> "AI"; "llm" -> "LLM"; else -> s.title
}

private fun platformLabel(os: Int, version: String): String = "${when (os) { 2 -> "Android"; 3 -> "macOS"; 4 -> "Windows"; else -> "iOS" }} $version"

private fun mono(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal, align: TextAlign? = null, spacing: TextUnit = TextUnit.Unspecified) =
    TextStyle(color = color, fontSize = size, fontFamily = InstrumentMono, fontWeight = weight, textAlign = align ?: TextAlign.Unspecified, letterSpacing = spacing)

/**
 * Two leaderboard runs head to head: who leads overall and by how much, the shape of both across the six sections, every
 * section as mirrored bars that open into each test's numbers (the better one lit in its side's colour), and the facts of
 * both devices. [a] and [b] are board entries; their full runs are loaded here.
 */
@Composable
fun BenchmarkCompareOverlay(a: BenchmarkDto, rankA: Int, b: BenchmarkDto, rankB: Int, online: Boolean, onDismiss: () -> Unit) {
    var swapped by remember { mutableStateOf(false) }
    var details by remember(a.id, b.id) { mutableStateOf<Pair<BenchmarkDetailDto, BenchmarkDetailDto>?>(null) }
    var error by remember(a.id, b.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(a.id, b.id, attempt, online) {
        if (details != null) return@LaunchedEffect
        error = null
        if (!online) { error = "Offline"; return@LaunchedEffect }
        error = try {
            coroutineScope {
                val da = async { ApiClient.api.fetchBenchmarkDetail(a.id) }
                val db = async { ApiClient.api.fetchBenchmarkDetail(b.id) }
                details = da.await() to db.await()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) "One of these runs is no longer on the board" else "Server error ${e.code()}"
        } catch (e: Exception) {
            e.message ?: "Couldn't load the two runs"
        }
    }

    val entryA = if (swapped) b else a
    val entryB = if (swapped) a else b
    val shownRankA = if (swapped) rankB else rankA
    val shownRankB = if (swapped) rankA else rankB
    val comparison = remember(details, swapped) {
        details?.let { (da, db) -> if (swapped) BenchmarkComparison.of(db, da) else BenchmarkComparison.of(da, db) }
    }
    var open by remember { mutableStateOf(setOf<String>()) }

    Box(Modifier.fillMaxSize().background(Forge.bg.copy(alpha = 0.9f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(Forge.raised)
                .border(1.dp, Forge.ink0.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("COMPARE", style = mono(12.sp, Forge.ink0, FontWeight.Black, spacing = 2.sp))
                Spacer(Modifier.weight(1f))
                RoundButton("⇄", onClick = { swapped = !swapped })
                Spacer(Modifier.width(8.dp))
                RoundButton("✕", onClick = onDismiss)
            }
            Spacer(Modifier.height(8.dp))
            // which colour is which, always in view
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendName(deviceDisplayName(entryA.deviceManufacturer, entryA.deviceModel), CompareA, Modifier.weight(1f), end = false)
                Text("VS", style = mono(8.sp, Forge.ink2, FontWeight.Black), modifier = Modifier.padding(horizontal = 8.dp))
                LegendName(deviceDisplayName(entryB.deviceManufacturer, entryB.deviceModel), CompareB, Modifier.weight(1f), end = true)
            }
            Spacer(Modifier.height(12.dp))

            when {
                comparison != null -> Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        DeviceCard(entryA, shownRankA, comparison.a, 'A', CompareA, Modifier.weight(1f).fillMaxHeight())
                        Text("VS", style = mono(9.sp, Forge.ink2, FontWeight.Black), modifier = Modifier.padding(horizontal = 6.dp))
                        DeviceCard(entryB, shownRankB, comparison.b, 'B', CompareB, Modifier.weight(1f).fillMaxHeight())
                    }
                    Verdict(comparison, entryA, entryB)
                    Card {
                        Text("PROFILE", style = mono(10.sp, Forge.ink1, FontWeight.Black, spacing = 1.sp))
                        Text("Each axis: the better of the two reaches the edge", style = mono(8.sp, Forge.ink2))
                        RadarChart(comparison.sections, Modifier.fillMaxWidth().height(250.dp).padding(top = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp)) {
                        Text("SECTION BY SECTION", style = mono(10.sp, Forge.ink1, FontWeight.Black, spacing = 1.sp), modifier = Modifier.weight(1f))
                        val all = comparison.sections.map { it.key }.toSet()
                        Text(if (open.containsAll(all)) "CLOSE ALL" else "OPEN ALL", style = mono(9.sp, Forge.phaseMeasured, FontWeight.Black),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = if (open.containsAll(all)) emptySet() else all }
                                .padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                    for (s in comparison.sections) {
                        SectionDuel(s, s.key in open) { open = if (s.key in open) open - s.key else open + s.key }
                    }
                    Facts(entryA, comparison.a, entryB, comparison.b)
                    Spacer(Modifier.height(4.dp))
                }
                error != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠", fontSize = 30.sp, color = Ramp.at(0.5f))
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", style = mono(11.sp, Forge.ink0, FontWeight.Bold, TextAlign.Center))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { attempt++ }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Forge.ink0)) {
                            Text("RETRY", style = mono(10.sp, Forge.bg, FontWeight.Bold))
                        }
                    }
                }
                else -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Forge.phasePreFlight, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("LOADING BOTH RUNS…", style = mono(10.sp, Forge.ink0.copy(alpha = 0.4f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(Forge.ink0.copy(alpha = 0.08f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = Forge.ink0, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LegendName(name: String, color: Color, modifier: Modifier, end: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        if (!end) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(name, style = mono(10.sp, color, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp).weight(1f, fill = false))
        if (end) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Forge.surface)
            .border(1.dp, Forge.hairline, RoundedCornerShape(16.dp)).padding(14.dp),
        content = content
    )
}

@Composable
private fun DeviceCard(e: BenchmarkDto, rank: Int, d: BenchmarkDetailDto, letter: Char, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.07f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text("$letter", style = mono(11.sp, Forge.bg, FontWeight.Black))
            }
            Spacer(Modifier.weight(1f))
            if (rank > 0) Text("#$rank", style = mono(12.sp, Forge.ink1, FontWeight.Black))
        }
        Spacer(Modifier.height(8.dp))
        Text(deviceDisplayName(e.deviceManufacturer, e.deviceModel), style = mono(12.sp, Forge.ink0, FontWeight.Bold), maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(listOfNotNull(d.socModel ?: e.socModel, d.gpuName ?: e.gpuName).joinToString(" · "), style = mono(8.sp, Forge.ink0.copy(alpha = 0.45f)),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(platformLabel(d.os, d.osVersion), style = mono(8.sp, Forge.ink0.copy(alpha = 0.45f)), maxLines = 1)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Text("%,d".format(d.totalScore), style = mono(20.sp, color, FontWeight.Black))
        Text("TOTAL POINTS", style = mono(7.sp, Forge.ink2, FontWeight.Bold, spacing = 0.5.sp))
    }
}

@Composable
private fun Verdict(c: BenchmarkComparison, ea: BenchmarkDto, eb: BenchmarkDto) {
    val nameA = deviceDisplayName(ea.deviceManufacturer, ea.deviceModel)
    val nameB = deviceDisplayName(eb.deviceManufacturer, eb.deviceModel)
    val split = remember { Animatable(0.5f) }
    LaunchedEffect(c.totalShareA) { split.animateTo(c.totalShareA, tween(800, easing = FastOutSlowInEasing)) }
    Card {
        Text(c.headline(nameA, nameB), style = mono(13.sp, if (c.total.winner == Lead.A || c.total.winner == Lead.B) leadColor(c.total.winner) else Forge.ink0, FontWeight.Black))
        Spacer(Modifier.height(3.dp))
        Text(c.sectionsLine(), style = mono(9.sp, Forge.ink1))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            val f = split.value.coerceIn(0.02f, 0.98f)
            Box(Modifier.weight(f).fillMaxHeight().background(Brush.horizontalGradient(listOf(CompareA.copy(alpha = 0.55f), CompareA))))
            Spacer(Modifier.width(2.dp).fillMaxHeight().background(Forge.raised))
            Box(Modifier.weight(1f - f).fillMaxHeight().background(Brush.horizontalGradient(listOf(CompareB, CompareB.copy(alpha = 0.55f)))))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("%,d".format(c.a.totalScore), style = mono(10.sp, CompareA, FontWeight.Black))
            Spacer(Modifier.weight(1f))
            Text("SHARE OF BOTH TOTALS", style = mono(7.sp, Forge.ink2, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text("%,d".format(c.b.totalScore), style = mono(10.sp, CompareB, FontWeight.Black))
        }
    }
}

/** A hexagon of the sections: each axis scaled so the better of the two touches the edge, A over B. */
@Composable
private fun RadarChart(sections: List<ComparedSection>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(sections) { reveal.snapTo(0f); reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val grid = Forge.ink0
    Canvas(modifier) {
        val n = sections.size
        if (n < 3) return@Canvas
        val c = center
        val radius = size.minDimension / 2f * 0.72f
        fun at(i: Int, f: Float): Offset {
            val angle = -PI / 2 + 2 * PI * i / n
            return Offset(c.x + (cos(angle) * radius * f).toFloat(), c.y + (sin(angle) * radius * f).toFloat())
        }
        fun ring(f: Float) = Path().apply { for (i in 0 until n) { val p = at(i, f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
        for (level in 1..4) drawPath(ring(level / 4f), grid.copy(alpha = if (level == 4) 0.16f else 0.06f), style = Stroke(1.dp.toPx()))
        for (i in 0 until n) drawLine(grid.copy(alpha = 0.07f), c, at(i, 1f), 1.dp.toPx())

        fun shape(shares: List<Float>, color: Color) {
            val path = Path().apply {
                shares.forEachIndexed { i, s -> val p = at(i, s * reveal.value); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            drawPath(path, color.copy(alpha = 0.16f))
            drawPath(path, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            shares.forEachIndexed { i, s -> drawCircle(color, 3.dp.toPx(), at(i, s * reveal.value)) }
        }
        shape(sections.map { it.shareB }, CompareB)
        shape(sections.map { it.shareA }, CompareA)

        sections.forEachIndexed { i, s ->
            val layout = measurer.measure(AnnotatedString(shortTitle(s)), mono(10.sp, leadColor(s.duel.winner), FontWeight.Black))
            val p = at(i, 1.2f)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

/** One side's half of a mirrored bar: A grows from the middle to the left, B to the right. */
@Composable
private fun HalfBar(share: Float, color: Color, strong: Boolean, towardStart: Boolean, height: Dp, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(share) { grow.animateTo(share.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing)) }
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Forge.ink0.copy(alpha = 0.05f))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(grow.value).align(if (towardStart) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(RoundedCornerShape(height / 2)).background(color.copy(alpha = if (strong) 1f else 0.4f))
        )
    }
}

private fun strong(duel: Duel, side: Lead) = duel.winner == side || duel.winner == Lead.EVEN

@Composable
private fun SectionDuel(s: ComparedSection, open: Boolean, onToggle: () -> Unit) {
    val accent = sectionColor(s.key)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.surface)
            .border(1.dp, accent.copy(alpha = if (open) 0.45f else 0.14f), RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreCell(s.scoreA, s.duel, Lead.A, Modifier.width(58.dp))
            HalfBar(s.shareA, CompareA, strong(s.duel, Lead.A), towardStart = true, height = 8.dp, modifier = Modifier.weight(1f))
            Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shortTitle(s), style = mono(11.sp, accent, FontWeight.Black, spacing = 1.sp))
                Text(if (open) "▾" else "▸", style = mono(9.sp, Forge.ink2))
            }
            HalfBar(s.shareB, CompareB, strong(s.duel, Lead.B), towardStart = false, height = 8.dp, modifier = Modifier.weight(1f))
            ScoreCell(s.scoreB, s.duel, Lead.B, Modifier.width(58.dp))
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            for (g in s.groups) GroupDuel(g, accent)
        }
    }
}

@Composable
private fun ScoreCell(score: Int?, duel: Duel, side: Lead, modifier: Modifier) {
    val won = duel.winner == side
    val lost = (duel.winner == Lead.A || duel.winner == Lead.B) && !won
    val end = side == Lead.B
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(score?.let { "%,d".format(it) } ?: "—",
            style = mono(13.sp, when { won -> leadColor(side); lost -> Forge.ink0.copy(alpha = 0.45f); else -> Forge.ink0 }, FontWeight.Black))
        val note = when {
            won -> duel.advantage?.let { advantageText(it) }
            duel.winner == Lead.EVEN -> "EVEN"
            else -> null
        }
        Text(note ?: " ", style = mono(8.sp, if (won) leadColor(side) else Forge.ink2, FontWeight.Black))
    }
}

@Composable
private fun GroupDuel(g: ComparedGroup, accent: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.pointsA?.let { "%,d".format(it) } ?: "", style = mono(9.sp, pointsColor(g.duel, Lead.A), FontWeight.Black), modifier = Modifier.weight(1f))
            Text(g.title.uppercase(), style = mono(8.sp, accent.copy(alpha = 0.9f), FontWeight.Black, TextAlign.Center, 0.5.sp),
                modifier = Modifier.weight(1.6f), maxLines = 2)
            Text(g.pointsB?.let { "%,d".format(it) } ?: "", style = mono(9.sp, pointsColor(g.duel, Lead.B), FontWeight.Black, TextAlign.End),
                modifier = Modifier.weight(1f))
        }
        if (g.pointsA != null || g.pointsB != null) {
            Row(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                HalfBar(g.shareA, CompareA, strong(g.duel, Lead.A), towardStart = true, height = 4.dp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                HalfBar(g.shareB, CompareB, strong(g.duel, Lead.B), towardStart = false, height = 4.dp, modifier = Modifier.weight(1f))
            }
        }
        for (r in g.rows) RowDuel(r)
    }
}

private fun pointsColor(duel: Duel, side: Lead): Color = when {
    duel.winner == side -> leadColor(side)
    duel.winner == Lead.A || duel.winner == Lead.B -> Forge.ink0.copy(alpha = 0.45f)
    else -> Forge.ink1
}

@Composable
private fun RowDuel(r: ComparedRow) {
    Column(Modifier.fillMaxWidth().padding(top = 7.dp)) {
        Text(r.label, style = mono(8.sp, Forge.ink2, align = TextAlign.Center), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.Top) {
            ValueCell(r.a, r.duel, Lead.A, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ValueCell(r.b, r.duel, Lead.B, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ValueCell(text: String?, duel: Duel, side: Lead, modifier: Modifier) {
    val won = duel.winner == side
    val lost = (duel.winner == Lead.A || duel.winner == Lead.B) && !won
    val color = when { won -> leadColor(side); lost -> Forge.ink0.copy(alpha = 0.42f); else -> Forge.ink0.copy(alpha = 0.85f) }
    Text(
        buildAnnotatedString {
            append(text ?: "—")
            if (won) duel.advantage?.let { withStyle(SpanStyle(fontSize = 8.sp, fontWeight = FontWeight.Black)) { append("  " + advantageText(it)) } }
        },
        style = mono(10.sp, color, if (won) FontWeight.Bold else FontWeight.Normal, if (side == Lead.B) TextAlign.End else TextAlign.Start),
        modifier = modifier
    )
}

@Composable
private fun Facts(ea: BenchmarkDto, da: BenchmarkDetailDto, eb: BenchmarkDto, db: BenchmarkDetailDto) {
    val facts = listOf(
        "CHIP" to (da.socModel to db.socModel),
        "GPU" to (da.gpuName to db.gpuName),
        "SYSTEM" to (platformLabel(da.os, da.osVersion) to platformLabel(db.os, db.osVersion)),
        "BUILD" to (da.osBuild to db.osBuild),
        "APP" to (da.appVersion to db.appVersion),
        "RUN DATE" to (da.createdAt.take(10) to db.createdAt.take(10)),
        "RUNS ON THE BOARD" to ("${ea.runs}" to "${eb.runs}"),
    )
    Card {
        Text("DEVICES", style = mono(10.sp, Forge.ink1, FontWeight.Black, spacing = 1.sp))
        for ((label, values) in facts) {
            Text(label, style = mono(7.sp, Forge.ink2, FontWeight.Bold, TextAlign.Center, 0.5.sp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row {
                Text(values.first ?: "—", style = mono(9.sp, CompareA.copy(alpha = 0.9f)), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(values.second ?: "—", style = mono(9.sp, CompareB.copy(alpha = 0.9f), align = TextAlign.End), modifier = Modifier.weight(1f))
            }
        }
    }
}
