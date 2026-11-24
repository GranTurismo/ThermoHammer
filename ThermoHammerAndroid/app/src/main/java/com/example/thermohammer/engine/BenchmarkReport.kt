package com.example.thermohammer.engine

import com.example.thermohammer.engine.ai.AiTuner
import com.example.thermohammer.engine.cpu.CpuTest

/** Plain-text report of a benchmark run — every raw number, for sharing or pasting into a bug report. */
object BenchmarkReport {

    fun text(r: BenchmarkResult, device: String, android: String, soc: String, appVersion: String, time: String): String =
        buildString {
            appendLine("ThermoHammer benchmark report · app $appVersion · $time")
            appendLine("$device · Android $android · SoC $soc · GPU ${r.gpuName.ifBlank { "?" }}")
            appendLine("TOTAL %,d = CPU %,d + MEM %,d + GPU %,d + UX %,d + AI %,d · validity flags %d".format(
                r.total, r.cpuScore, r.memScore, r.gpuScore, r.uxScore, r.aiScore, r.validityFlags))
            r.only?.let { s -> appendLine("PARTIAL RUN (test build) · only ${BenchSection.entries.filter { it in s }.joinToString(", ") { it.label }} · the rest scores 0") }
            appendLine("Start · CPU share %.0f%% · waited %d s for background work".format(r.startCpuShare * 100, r.quietWaitSec))
            r.cpu?.let { cpu ->
                for (t in CpuTest.entries) {
                    val run = cpu.runs.firstOrNull { it.test == t }
                    appendLine("CPU %d %s · %,d pts · %.2f · %s".format(t.number, t.title, cpu.points(t), cpu.value(t),
                        run?.rates?.entries?.joinToString(", ") { (k, v) -> "$k %.1f".format(v) } ?: "not run"))
                }
            }
            r.mem?.let { m ->
                appendLine("MEM 1 RAM · %,d pts · read %.2f, write %.2f, copy %.2f GB/s · latency %.2f ns".format(
                    m.ramPoints, m.ram.read, m.ram.write, m.ram.copy, m.ram.latencyNs))
                appendLine("MEM 2 STORAGE · %,d pts · seq read %.0f, seq write %.0f MB/s · random read %.0f, random write %.0f IOPS · %d MB file, %s".format(
                    m.storagePoints, m.storage.seqRead, m.storage.seqWrite, m.storage.randomRead, m.storage.randomWrite,
                    m.storage.fileMb, if (m.storage.direct) "direct I/O" else "cached I/O, dropped per block"))
                appendLine("MEM 3 DATABASE · %,d pts · insert %.0f, update %.0f tx/s · query %.0f/s".format(
                    m.dbPoints, m.db.insert, m.db.update, m.db.query))
            }
            for (t in r.tests) {
                if (!t.supported) {
                    appendLine("GPU %d %s · 0 pts · not run: %s".format(t.test.number, t.test.title,
                        if (r.only != null && BenchSection.GPU !in r.only) "skipped (partial run)" else r.rayTracingProblem ?: "unsupported"))
                    continue
                }
                appendLine("GPU %d %s · %,d pts · avg %.2f fps · 1%% low %.2f · stability %.0f%% · %d frames · temp %s→%s °C".format(
                    t.test.number, t.test.title, t.points, t.avgFps, t.lowFps, t.stabilityPct, t.frames,
                    t.startTempC?.let { "%.0f".format(it) } ?: "?", t.endTempC?.let { "%.0f".format(it) } ?: "?"))
            }
            r.ux?.let { ux ->
                val w = ux.web
                appendLine("UX 1 WEB BROWSING · %,d pts · %s".format(ux.webPoints,
                    w?.let { "layout %.0f, canvas %.0f, css %.0f, svg %.0f, js %.1f".format(it.layout, it.canvas, it.css, it.svg, it.js) }
                        ?: "did not finish"))
                appendLine("UX 2 WEB SCROLLING · %,d pts · %s".format(ux.scrollPoints,
                    ux.scroll?.let { "held %.0f px/s, %.1f%% frames dropped".format(it.speed, it.droppedPct) } ?: "did not finish"))
                appendLine("UX 3 PHOTO GALLERY · %,d pts · %s".format(ux.galleryPoints,
                    ux.gallery?.let { "JPEG %.2f, WebP %.2f, AVIF %.2f MP/s".format(it.jpeg, it.webp, it.avif) } ?: "did not finish"))
                appendLine("UX 4 PHOTO EDITING · %,d pts · %.2f MP/s".format(ux.photoPoints, ux.photoMps))
                appendLine("UX 5 VIDEO PLAYBACK · %,d pts · %s".format(ux.videoPoints,
                    ux.video?.codecs?.joinToString(", ") { c ->
                        if (c.fps > 0) "%s %.2f fps (%s %s)".format(c.clip.label, c.fps, if (c.hardware) "HW" else "SW", c.decoder ?: "?")
                        else "${c.clip.label} 0 (${c.error ?: "unsupported"})"
                    } ?: "did not run"))
                ux.video?.let { v ->
                    for (c in VideoCodec.entries) appendLine("  plays %-5s %s".format(c.short, VideoGrid.text(VideoGrid.playback(v, c))))
                }
                appendLine("UX 6 VIDEO EXPORT · %,d pts · %s".format(ux.exportPoints,
                    ux.export?.jobs?.joinToString(", ") { r ->
                        if (r.fps > 0) "%s %s %.2f fps %.1f dB%s (%s %s)".format(r.job.label, r.job.res, r.fps, r.psnr,
                            if (r.psnr < VideoGrid.MIN_PSNR) " LOW QUALITY" else "", if (r.hardware) "HW" else "SW", r.encoder ?: "?")
                        else "${r.job.label} ${r.job.res} 0 (${r.error ?: "unsupported"})"
                    } ?: "did not run"))
                ux.export?.let { e ->
                    for (c in listOf(VideoCodec.AVC, VideoCodec.HEVC, VideoCodec.AV1))
                        appendLine("  exports %-5s %s".format(c.short, VideoGrid.text(VideoGrid.export(e, c))))
                }
                val q = ux.qr
                appendLine("UX 7 QR SCANNING · %,d pts · %s".format(ux.qrPoints,
                    q?.let { "ZXing %.2f/s (%d missed), ML Kit %s".format(it.zxingRate, it.zxingMissed,
                        it.mlkitRate?.let { m -> "%.2f/s".format(m) } ?: "n/a${it.mlkitError?.let { e -> " ($e)" } ?: ""}") }
                        ?: "did not run"))
                ux.error?.let { appendLine("UX error: $it") }
            }
            r.ai?.let { ai ->
                appendLine("AI · %,d pts = CPU %,d · GPU %,d (each half)".format(ai.score, ai.accelScore(AiAccel.CPU), ai.accelScore(AiAccel.GPU)))
                if (AiAccel.NPU in ai.shown)
                    appendLine("AI NPU (report only, not scored) · would be %,d pts · %s%s%s".format(ai.accelScore(AiAccel.NPU),
                        ai.npuRuntime ?: "not run: ${ai.npuProblem ?: "?"}",
                        ai.npuFiles?.let { " ($it)" } ?: "", if (ai.npuBurst) "" else " · default clocks (BURST failed)"))
                ai.npuLog?.let { appendLine("  NPU log: $it") }
                for (t in AiTest.entries) {
                    appendLine("AI %d %s · %s".format(t.number, t.title, ai.shown.joinToString(", ") { a ->
                        val run = ai.runs.firstOrNull { it.test == t && it.accel == a }
                        // a GPU's or an NPU's rate comes with how well its answer agreed with the CPU's (1.00 is a match)
                        if (run != null && run.ips > 0) "%s %.2f/s".format(a.label, run.ips) + (run.agreement?.let { " (agreement %.2f)".format(it) } ?: "")
                        else "${a.label} 0 (${run?.error ?: "not run"})"
                    }))
                }
                for (t in ai.tuning) AiTuner.lines(t).forEach { appendLine(it) }
                AiTuner.delegationLines(ai.delegation).forEach { appendLine(it) }
                ai.gpuPrecision?.forEach { appendLine(it) }
            }
        }
}
