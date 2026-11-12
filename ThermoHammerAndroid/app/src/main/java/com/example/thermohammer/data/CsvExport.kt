package com.example.thermohammer.data

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.Attribution
import com.example.thermohammer.engine.BenchmarkResult
import com.example.thermohammer.engine.EnvFlag
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.StampV2
import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.network.DeviceHammerStamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EXPORT CSV — a run's raw numbers for reviewers: UTF-8, commas, "." decimals, the header row first, no comment lines.
 * The columns are the same on every platform (features-spec §3); a stress run adds what Android records per stamp.
 * Pure logic, no Android.
 */
object CsvExport {

    /** "thermohammer-stress-honor-600-pro-20261112-1430.csv" */
    fun fileName(kind: String, device: String, millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "thermohammer-$kind-${slug(device)}-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = zone }.format(Date(millis))}.csv"

    /** Lower case, every run of other characters one "-": "HONOR 600 Pro" → "honor-600-pro". */
    fun slug(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "device" }

    /** The stamps' thermal state as its number (0–3, like iOS and Windows, so it charts beside throughput); empty if out of range. */
    fun thermalLevel(state: Int): String = if (state in 0..3) state.toString() else ""

    // ── stress ───────────────────────────────────────────────────────────────

    val STRESS_COLUMNS = listOf("elapsed_s", "throughput", "percent_of_baseline",
        "thermal_state",            // 0 nominal, 1 fair, 2 serious, 3 critical
        // what Android records with every stamp
        "cpu_temp_c", "skin_temp_c", "battery_temp_c", "battery_current_ma", "battery_voltage_mv", "thermal_headroom",
        "cpu_freq_pct", "cpu_cap_pct", "cpu_min_cap_pct", "scheduled_pct", "offline_cores", "attribution", "charging",
        "battery_saver", "gpu_fps", "gpu_frame_p95_ms", "gpu_freq_pct")

    /**
     * The user's own stress run, one row per 1-s stamp. throughput = work per second of the run's main channel (CPU
     * iterations/s; a GPU-only run's frames/s × 100, as it is scored); an empty cell = not measured on this phone.
     */
    fun stress(stamps: List<StampV2>, baselineIps: Double): String = buildString {
        line(STRESS_COLUMNS)
        for (s in stamps) line(listOf(
            num(s.tNs / 1e9, 1),
            s.ipsTotal.toString(),
            if (baselineIps > 0) num(s.ipsTotal / baselineIps * 100, 1) else "",
            thermalLevel(s.thermalStatus),
            if (s.cpuTempMilliC > 0) num(s.cpuTempMilliC / 1000.0, 1) else "",
            if (s.skinTempMilliC >= 0) num(s.skinTempMilliC / 1000.0, 1) else "",
            if (s.battTempMilliC > 0) num(s.battTempMilliC / 1000.0, 1) else "",
            s.battCurrentUa?.let { num(it / 1000.0, 1) } ?: "",
            if (s.battVoltageMv > 0) s.battVoltageMv.toString() else "",
            s.thermalHeadroom?.let { num(it.toDouble(), 3) } ?: "",
            num(s.freqRatioAvg * 100.0, 1),
            num(s.capRatioAvg * 100.0, 1),
            num(s.minCapRatio * 100.0, 1),
            num(s.schedFracAvg * 100.0, 1),
            s.offlineCores.toString(),
            Attribution.of(s.attribution).name.lowercase(Locale.ROOT),
            if (s.envFlags and EnvFlag.CHARGING != 0) "1" else "0",
            if (s.envFlags and EnvFlag.BATTERY_SAVER != 0) "1" else "0",
            if (s.gpuFps >= 0) num(s.gpuFps, 2) else "",
            if (s.gpuFrameP95Ms >= 0) num(s.gpuFrameP95Ms.toDouble(), 2) else "",
            if (s.gpuFreqRatio >= 0) num(s.gpuFreqRatio * 100.0, 1) else ""
        ))
    }

    /** A leaderboard stress run from `/stamps/{id}`: what the server keeps. */
    fun leaderboardStress(stamps: List<DeviceHammerStamp>): String = buildString {
        line(listOf("elapsed_s", "score", "thermal_state"))   // thermal_state: 0 nominal, 1 fair, 2 serious, 3 critical
        for (s in stamps) line(listOf(num(s.elapsedMs / 1000.0, 1), s.score.toString(), thermalLevel(s.thermalState)))
    }

    // ── benchmark ────────────────────────────────────────────────────────────

    /**
     * Every raw number of the text report (BenchmarkReport), one row each, then each section's score and the total:
     * `section,metric,value,unit`.
     */
    fun benchmark(r: BenchmarkResult): String {
        val rows = ArrayList<List<String>>()
        fun add(section: String, metric: String, value: Double, unit: String, decimals: Int = 2) =
            rows.add(listOf(section, metric, num(value, decimals), unit))
        fun add(section: String, metric: String, value: Int, unit: String) = rows.add(listOf(section, metric, value.toString(), unit))
        fun flag(section: String, metric: String, on: Boolean) = add(section, metric, if (on) 1 else 0, "bool")

        add("run", "start_cpu_share", r.startCpuShare * 100.0, "%", 0)
        add("run", "quiet_wait", r.quietWaitSec, "s")
        add("run", "validity_flags", r.validityFlags, "flags")

        r.cpu?.let { cpu ->
            for (t in CpuTest.entries) {
                val k = key(t.title)
                add("cpu", "${k}_points", cpu.points(t), "points")
                add("cpu", "${k}_geomean", cpu.value(t), "ops/s")
                cpu.runs.firstOrNull { it.test == t }?.rates?.forEach { (name, rate) -> add("cpu", "${k}_${key(name)}", rate, "ops/s") }
            }
        }
        r.mem?.let { m ->
            add("mem", "ram_points", m.ramPoints, "points")
            add("mem", "ram_read", m.ram.read, "GB/s"); add("mem", "ram_write", m.ram.write, "GB/s")
            add("mem", "ram_copy", m.ram.copy, "GB/s"); add("mem", "ram_latency", m.ram.latencyNs, "ns")
            add("mem", "storage_points", m.storagePoints, "points")
            add("mem", "storage_seq_read", m.storage.seqRead, "MB/s"); add("mem", "storage_seq_write", m.storage.seqWrite, "MB/s")
            add("mem", "storage_random_read", m.storage.randomRead, "IOPS"); add("mem", "storage_random_write", m.storage.randomWrite, "IOPS")
            add("mem", "storage_file", m.storage.fileMb, "MB"); flag("mem", "storage_direct_io", m.storage.direct)
            add("mem", "db_points", m.dbPoints, "points")
            add("mem", "db_insert", m.db.insert, "tx/s"); add("mem", "db_update", m.db.update, "tx/s")
            add("mem", "db_query", m.db.query, "queries/s")
        }
        for (t in r.tests) {
            val k = key(t.test.title)
            add("gpu", "${k}_points", t.points, "points")
            flag("gpu", "${k}_supported", t.supported)
            if (!t.supported) continue
            add("gpu", "${k}_avg_fps", t.avgFps, "fps"); add("gpu", "${k}_low_1pct_fps", t.lowFps, "fps")
            add("gpu", "${k}_stability", t.stabilityPct.toDouble(), "%", 1); add("gpu", "${k}_frames", t.frames, "frames")
            t.startTempC?.let { add("gpu", "${k}_start_temp", it.toDouble(), "C", 1) }
            t.endTempC?.let { add("gpu", "${k}_end_temp", it.toDouble(), "C", 1) }
        }
        r.ux?.let { ux ->
            add("ux", "web_points", ux.webPoints, "points")
            ux.web?.let { w ->
                add("ux", "web_layout", w.layout, "complexity"); add("ux", "web_canvas", w.canvas, "complexity")
                add("ux", "web_css", w.css, "complexity"); add("ux", "web_svg", w.svg, "complexity"); add("ux", "web_js", w.js, "ops/s")
            }
            add("ux", "scroll_points", ux.scrollPoints, "points")
            ux.scroll?.let { add("ux", "scroll_speed", it.speed, "px/s"); add("ux", "scroll_dropped", it.droppedPct, "%") }
            add("ux", "gallery_points", ux.galleryPoints, "points")
            ux.gallery?.let {
                add("ux", "gallery_jpeg", it.jpeg, "MP/s"); add("ux", "gallery_webp", it.webp, "MP/s"); add("ux", "gallery_avif", it.avif, "MP/s")
            }
            add("ux", "photo_points", ux.photoPoints, "points")
            add("ux", "photo_edit", ux.photoMps, "MP/s")
            add("ux", "video_points", ux.videoPoints, "points")
            ux.video?.codecs?.forEach { c ->
                val k = "video_${key(c.clip.label)}"
                add("ux", "${k}_fps", c.fps, "fps"); flag("ux", "${k}_hardware", c.hardware)
            }
            add("ux", "export_points", ux.exportPoints, "points")
            ux.export?.jobs?.forEach { j ->
                val k = "export_${key("${j.job.label} ${j.job.res}")}"
                add("ux", "${k}_fps", j.fps, "fps"); add("ux", "${k}_psnr", j.psnr, "dB", 1); flag("ux", "${k}_hardware", j.hardware)
            }
            add("ux", "qr_points", ux.qrPoints, "points")
            ux.qr?.let { q ->
                add("ux", "qr_zxing", q.zxingRate, "scans/s"); add("ux", "qr_zxing_missed", q.zxingMissed, "frames")
                q.mlkitRate?.let { add("ux", "qr_mlkit", it, "scans/s") }
            }
        }
        r.ai?.let { ai ->
            for (a in AiAccel.entries) add("ai", "${key(a.label)}_points", ai.accelScore(a), "points")
            for (t in AiTest.entries) for (a in AiAccel.entries)
                add("ai", "${key(t.title)}_${key(a.label)}", ai.ips(t, a), "inferences/s")
        }
        r.llm?.let { llm ->
            add("llm", "prefill_points", llm.prefillPoints, "points"); add("llm", "decode_points", llm.decodePoints, "points")
            add("llm", "prefill", llm.prefill, "tokens/s"); add("llm", "decode", llm.decode, "tokens/s")
            add("llm", "free_memory", llm.freeGb, "GB"); add("llm", "largest_model", llm.largestModel, "B parameters")
            for (a in LlmAccel.entries) {
                val run = llm.run(a)
                add("llm", "${key(a.label)}_prefill", run?.prefill ?: 0.0, "tokens/s")
                add("llm", "${key(a.label)}_decode", run?.decode ?: 0.0, "tokens/s")
                if (a == LlmAccel.CPU && run != null) add("llm", "cpu_threads", run.threads, "threads")
            }
        }
        for ((section, points) in listOf("cpu" to r.cpuScore, "mem" to r.memScore, "gpu" to r.gpuScore, "ux" to r.uxScore,
                "ai" to r.aiScore, "llm" to r.llmScore)) add("score", section, points, "points")
        add("score", "total", r.total, "points")
        return buildString {
            line(listOf("section", "metric", "value", "unit"))
            rows.forEach { line(it) }
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────

    /** "MATRIX MULTIPLY" → "matrix_multiply", "H.264 4K" → "h_264_4k". */
    fun key(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** A number with "." whatever the phone's language, no thousands separators. */
    fun num(v: Double, decimals: Int): String = if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.US, "%.${decimals}f", v)

    /** RFC 4180: a field with a comma, quote or line break goes in quotes, its quotes doubled. */
    fun field(v: String): String = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun StringBuilder.line(fields: List<String>) {
        fields.joinTo(this, ",") { field(it) }
        append('\n')
    }
}
