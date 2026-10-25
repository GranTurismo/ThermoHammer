package com.example.thermohammer.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiResult
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.BenchmarkResult
import com.example.thermohammer.engine.BenchmarkTestResult
import com.example.thermohammer.engine.MemResult
import com.example.thermohammer.engine.MemTest
import com.example.thermohammer.engine.UxResult
import com.example.thermohammer.engine.UxTest
import com.example.thermohammer.engine.ValidityFlag
import com.example.thermohammer.ui.theme.*

// ── GPU benchmark result — the score, then what each test delivered ──────────

@Composable
fun BenchmarkResultOverlay(
    result: BenchmarkResult,
    deviceName: String,
    isNetworkConnected: Boolean,
    isSubmitting: Boolean,
    submitMessage: String?,
    submitError: String?,
    onSubmit: () -> Unit,
    onCopyReport: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Forge.bg.copy(alpha = 0.88f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        OverlayCard(width = 340) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel("THERMOHAMMER BENCHMARK", size = 9f)
                Spacer(Modifier.height(10.dp))
                Text(
                    "%,d".format(result.total),
                    style = TextStyle(color = Forge.ink0, fontSize = 52.sp, fontFamily = InstrumentMono,
                        fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                )
                MonoLabel("TOTAL POINTS", size = 10f)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionScore("CPU", result.cpuScore, cpuColor())
                    SectionScore("MEM", result.memScore, memColor())
                    SectionScore("GPU", result.gpuScore, Forge.phaseCalibration)
                    SectionScore("UX", result.uxScore, Forge.phaseMeasured)
                    SectionScore("AI", result.aiScore, aiColor())
                }
                Spacer(Modifier.height(8.dp))
                Text(deviceName, style = ThermoType.data(12f, Forge.ink1, FontWeight.Bold), textAlign = TextAlign.Center)
                if (result.gpuName.isNotBlank()) {
                    Text(result.gpuName, style = ThermoType.annotation(9f, Forge.ink2), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                result.cpu?.let { cpu ->
                    for (t in com.example.thermohammer.engine.cpu.CpuTest.entries) {
                        CpuCard(t, cpu)
                        Spacer(Modifier.height(10.dp))
                    }
                }
                result.mem?.let { mem ->
                    RamCard(mem)
                    Spacer(Modifier.height(10.dp))
                    StorageCard(mem)
                    Spacer(Modifier.height(10.dp))
                    DatabaseCard(mem)
                    Spacer(Modifier.height(10.dp))
                }
                for (t in result.tests) {
                    TestCard(t)
                    Spacer(Modifier.height(10.dp))
                }
                result.ux?.let { ux ->
                    WebCard(ux)
                    Spacer(Modifier.height(10.dp))
                    ScrollCard(ux)
                    Spacer(Modifier.height(10.dp))
                    GalleryCard(ux)
                    Spacer(Modifier.height(10.dp))
                    PhotoCard(ux)
                    Spacer(Modifier.height(10.dp))
                    VideoCard(ux)
                    Spacer(Modifier.height(10.dp))
                    QrCard(ux)
                    Spacer(Modifier.height(10.dp))
                    result.ai?.let { ai ->
                        AiSummaryCard(ai)
                        Spacer(Modifier.height(10.dp))
                        for (t in AiTest.entries) {
                            AiCard(t, ai)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    ux.error?.let {
                        Text(it, style = ThermoType.annotation(9f, Ramp.at(0.85f)), textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                if (!result.isVerified) {
                    Text(
                        validityNote(result.validityFlags),
                        style = ThermoType.annotation(9f, Ramp.at(0.5f)),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                when {
                    submitMessage != null -> Text(submitMessage, style = ThermoType.data(11f, Forge.phaseMeasured, FontWeight.Bold),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
                    isSubmitting -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Forge.phasePreFlight, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        MonoLabel("SUBMITTING…", size = 10f, alpha = 0.7f)
                    }
                    isNetworkConnected -> PrimaryButton("SUBMIT TO LEADERBOARD", onSubmit, accent = Forge.phaseMeasured)
                    else -> Text("Offline — the run is saved; submit it from the home screen later.",
                        style = ThermoType.annotation(9f, Forge.ink1), textAlign = TextAlign.Center)
                }
                if (submitError != null) {
                    Text("⚠ $submitError", style = ThermoType.annotation(9f, Ramp.at(0.85f)),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("COPY REPORT", onCopyReport)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("CLOSE", onDismiss)
            }
        }
    }
}

@Composable
private fun TestCard(t: BenchmarkTestResult) {
    val accent = benchmarkColor(t.test)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GPU TEST ${t.test.number}", style = ThermoType.label(9f).copy(color = accent))
                Text(t.test.title, style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(t.points), style = ThermoType.data(16f, Forge.ink0, FontWeight.Black))
                Text("POINTS", style = ThermoType.annotation(7f, Forge.ink2))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!t.supported) {
            Text("This phone's GPU has no Vulkan ray tracing — the test can't run and scores 0.",
                style = ThermoType.annotation(9f, Forge.ink1))
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("AVG FPS", "%.1f".format(t.avgFps), Forge.ink0)
            Metric("1% LOW", "%.1f".format(t.lowFps), Forge.ink1)
            Metric("STABILITY", "%.0f%%".format(t.stabilityPct), Ramp.forCapacity(t.stabilityPct))
            Metric("TEMP", tempSpan(t.startTempC, t.endTempC), Forge.ink1)
        }
    }
}

@Composable
private fun SectionScore(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%,d".format(value), style = ThermoType.data(16f, color, FontWeight.Black))
        Text(label, style = ThermoType.annotation(8f, Forge.ink2))
    }
}

@Composable
private fun UxCard(test: UxTest, points: Int, content: @Composable ColumnScope.() -> Unit) {
    val accent = uxColor(test)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("UX TEST ${test.number}", style = ThermoType.label(9f).copy(color = accent))
                Text(test.title, style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(points), style = ThermoType.data(16f, Forge.ink0, FontWeight.Black))
                Text("POINTS", style = ThermoType.annotation(7f, Forge.ink2))
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun CpuCard(test: com.example.thermohammer.engine.cpu.CpuTest, cpu: com.example.thermohammer.engine.CpuResult) {
    val accent = cpuColor()
    val run = cpu.runs.firstOrNull { it.test == test }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CPU TEST ${test.number}" + if (test.multiCore) " · ALL CORES" else "",
                    style = ThermoType.label(9f).copy(color = accent))
                Text(test.title, style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(cpu.points(test)), style = ThermoType.data(16f, Forge.ink0, FontWeight.Black))
                Text("POINTS", style = ThermoType.annotation(7f, Forge.ink2))
            }
        }
        if (run != null) {
            Spacer(Modifier.height(6.dp))
            Text(run.rates.entries.joinToString("  ·  ") { (k, v) -> "$k " + if (v < 100) "%.1f".format(v) else "%,.0f".format(v) } + "  ops/s",
                style = ThermoType.annotation(8f, Forge.ink1))
        }
    }
}

@Composable
private fun MemCard(test: MemTest, points: Int, content: @Composable ColumnScope.() -> Unit) {
    val accent = memColor()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MEM TEST ${test.number}", style = ThermoType.label(9f).copy(color = accent))
                Text(test.title, style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(points), style = ThermoType.data(16f, Forge.ink0, FontWeight.Black))
                Text("POINTS", style = ThermoType.annotation(7f, Forge.ink2))
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun RamCard(mem: MemResult) = MemCard(MemTest.RAM, mem.ramPoints) {
    val r = mem.ram
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("READ GB/S", "%.1f".format(r.read), Forge.ink0)
        Metric("WRITE GB/S", "%.1f".format(r.write), Forge.ink0)
        Metric("COPY GB/S", "%.1f".format(r.copy), Forge.ink0)
        Metric("LATENCY NS", "%.0f".format(r.latencyNs), Forge.ink0)
    }
    Spacer(Modifier.height(4.dp))
    Text("Every core streaming a buffer far bigger than the caches; latency is one random access.",
        style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun StorageCard(mem: MemResult) = MemCard(MemTest.STORAGE, mem.storagePoints) {
    val r = mem.storage
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("SEQ READ MB/S", "%,.0f".format(r.seqRead), Forge.ink0)
        Metric("SEQ WRITE MB/S", "%,.0f".format(r.seqWrite), Forge.ink0)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("RANDOM READ IOPS", "%,.0f".format(r.randomRead), Forge.ink0)
        Metric("RANDOM WRITE IOPS", "%,.0f".format(r.randomWrite), Forge.ink0)
    }
    Spacer(Modifier.height(4.dp))
    Text("%d MB test file, deleted afterwards. 4 MB sequential blocks; random 4 KB blocks from 4 threads. %s".format(
        r.fileMb, if (r.direct) "Direct I/O." else "This phone's file system caches even direct I/O, so each block was dropped from RAM after use."),
        style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun DatabaseCard(mem: MemResult) = MemCard(MemTest.DATABASE, mem.dbPoints) {
    val r = mem.db
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("MESSAGES SAVED/S", "%,.0f".format(r.insert), Forge.ink0)
        Metric("MARKED READ/S", "%,.0f".format(r.update), Forge.ink0)
        Metric("CHATS OPENED/S", "%,.0f".format(r.query), Forge.ink0)
    }
    Spacer(Modifier.height(4.dp))
    Text("SQLite like a messaging app, with Android's own settings: 20,000 messages, each save its own transaction, each chat its latest 30 messages.",
        style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun WebCard(ux: UxResult) = UxCard(UxTest.WEB, ux.webPoints) {
    val w = ux.web
    if (w == null) {
        Text("The WebView test didn't finish on this phone.", style = ThermoType.annotation(9f, Forge.ink1))
        return@UxCard
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("LAYOUT", "%.0f".format(w.layout), Forge.ink0)
        Metric("CANVAS", "%.0f".format(w.canvas), Forge.ink0)
        Metric("CSS", "%.0f".format(w.css), Forge.ink0)
        Metric("SVG", "%.0f".format(w.svg), Forge.ink0)
        Metric("JS OPS/S", "%.0f".format(w.js), Forge.ink1)
    }
}

@Composable
private fun ScrollCard(ux: UxResult) = UxCard(UxTest.SCROLL, ux.scrollPoints) {
    val r = ux.scroll
    if (r == null) {
        Text("The scrolling test didn't finish on this phone.", style = ThermoType.annotation(9f, Forge.ink1))
        return@UxCard
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("SPEED HELD PX/S", "%,.0f".format(r.speed), Forge.ink0)
        Metric("DROPPED FRAMES", "%.1f%%".format(r.droppedPct), Forge.ink1)
    }
}

@Composable
private fun GalleryCard(ux: UxResult) = UxCard(UxTest.GALLERY, ux.galleryPoints) {
    val r = ux.gallery
    if (r == null) {
        Text("The gallery test didn't finish on this phone.", style = ThermoType.annotation(9f, Forge.ink1))
        return@UxCard
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("JPEG MP/S", "%.1f".format(r.jpeg), Forge.ink0)
        Metric("WEBP MP/S", "%.1f".format(r.webp), Forge.ink0)
        Metric("AVIF MP/S", if (r.avif > 0) "%.1f".format(r.avif) else "—", if (r.avif > 0) Forge.ink0 else Ramp.at(0.85f))
    }
}

@Composable
private fun PhotoCard(ux: UxResult) = UxCard(UxTest.PHOTO, ux.photoPoints) {
    Metric("MEGAPIXELS EDITED PER SECOND", "%.1f".format(ux.photoMps), Forge.ink0)
    Spacer(Modifier.height(4.dp))
    Text("Fisheye lens, blur, portrait bokeh, sharpening and a colour grade on a 12 MP photo, on the GPU.",
        style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun VideoCard(ux: UxResult) = UxCard(UxTest.VIDEO, ux.videoPoints) {
    val v = ux.video ?: return@UxCard
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (c in v.codecs) {
            Metric(c.codec.short + if (c.fps > 0) (if (c.hardware) " · HW" else " · SW") else " · N/A",
                if (c.fps > 0) "%.0f".format(c.fps) else "—", if (c.fps > 0) Forge.ink0 else Ramp.at(0.85f))
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Frames per second decoding 4K60 clips flat out; 298 fps per codec = 6000 points, an unsupported codec 0.",
        style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun QrCard(ux: UxResult) = UxCard(UxTest.QR, ux.qrPoints) {
    val q = ux.qr ?: return@UxCard
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric("ZXING SCANS/S", "%.1f".format(q.zxingRate), Forge.ink0)
        Metric("MISSED", "${q.zxingMissed}", if (q.zxingMissed > 0) Ramp.at(0.85f) else Forge.ink1)
        Metric("ML KIT SCANS/S", q.mlkitRate?.let { "%.1f".format(it) } ?: "—", Forge.ink1)
    }
    Spacer(Modifier.height(4.dp))
    Text("ML Kit (Google's scanner) is shown for reference and not scored.", style = ThermoType.annotation(8f, Forge.ink2))
}

@Composable
private fun AiSummaryCard(ai: AiResult) {
    val accent = aiColor()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI · 6 MODELS · INT8", style = ThermoType.label(9f).copy(color = accent))
                Text("CPU · GPU · NPU", style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("%,d".format(ai.score), style = ThermoType.data(16f, Forge.ink0, FontWeight.Black))
                Text("POINTS", style = ThermoType.annotation(7f, Forge.ink2))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (a in AiAccel.entries) {
                val pts = ai.accelScore(a)
                Metric("${a.label} POINTS", if (pts > 0) "%,d".format(pts) else "—", if (pts > 0) Forge.ink0 else Ramp.at(0.85f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(ai.npuRuntime?.let { "NPU: $it. CPU, GPU and NPU count a third each." }
            ?: "NPU: ${ai.npuProblem ?: "not available"} — 0 points there.",
            style = ThermoType.annotation(8f, Forge.ink2))
    }
}

@Composable
private fun AiCard(test: AiTest, ai: AiResult) {
    val accent = aiColor()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.surface)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text("AI TEST ${test.number}", style = ThermoType.label(9f).copy(color = accent))
        Text(test.title, style = ThermoType.data(13f, Forge.ink0, FontWeight.Black))
        Text(test.model, style = ThermoType.annotation(8f, Forge.ink2))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (a in AiAccel.entries) {
                val ips = ai.ips(test, a)
                Metric("${a.label} INF/S", if (ips > 0) (if (ips < 100) "%.1f" else "%,.0f").format(ips) else "—",
                    if (ips > 0) Forge.ink0 else Ramp.at(0.85f))
            }
        }
        ai.runs.filter { it.test == test && it.error != null && it.accel != AiAccel.NPU }.forEach {
            Text("${it.accel.label}: ${it.error}", style = ThermoType.annotation(8f, Ramp.at(0.85f)))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column {
        Text(value, style = ThermoType.data(12f, color, FontWeight.Bold))
        Text(label, style = ThermoType.annotation(7f, Forge.ink2))
    }
}

private fun tempSpan(a: Float?, b: Float?): String =
    if (a == null || b == null) "--" else "%.0f→%.0f°".format(a, b)

private fun validityNote(flags: Int): String {
    val notes = buildList {
        if (flags and ValidityFlag.POWER_EVENT != 0) add("charger connected during the run")
        if (flags and ValidityFlag.ENV_CAPPED != 0) add("battery saver was on")
        if (flags and ValidityFlag.COOLDOWN_TIMEOUT != 0) add("started warm")
        if (flags and ValidityFlag.INTERFERENCE != 0) add("the phone was busy with background work")
    }
    return "Unverified: " + notes.joinToString(", ") + "."
}

/** A test that could not be scored (e.g. the GPU failed to build a scene). */
@Composable
fun BenchmarkErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Forge.bg.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        OverlayCard {
            MonoTitle("BENCHMARK STOPPED", color = Ramp.at(0.85f))
            Spacer(Modifier.height(10.dp))
            Text(message, style = ThermoType.annotation(10f, Forge.ink1))
            Spacer(Modifier.height(18.dp))
            PrimaryButton("CLOSE", onDismiss)
        }
    }
}
