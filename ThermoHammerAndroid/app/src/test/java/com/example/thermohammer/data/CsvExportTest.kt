package com.example.thermohammer.data

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiResult
import com.example.thermohammer.engine.AiRun
import com.example.thermohammer.engine.AiTest
import com.example.thermohammer.engine.Attribution
import com.example.thermohammer.engine.BenchmarkResult
import com.example.thermohammer.engine.BenchmarkTest
import com.example.thermohammer.engine.BenchmarkTestResult
import com.example.thermohammer.engine.CpuResult
import com.example.thermohammer.engine.CpuScore
import com.example.thermohammer.engine.DbResult
import com.example.thermohammer.engine.EnvFlag
import com.example.thermohammer.engine.LlmAccel
import com.example.thermohammer.engine.LlmResult
import com.example.thermohammer.engine.LlmRun
import com.example.thermohammer.engine.MemResult
import com.example.thermohammer.engine.RamResult
import com.example.thermohammer.engine.StampV2
import com.example.thermohammer.engine.StorageResult
import com.example.thermohammer.engine.UxResult
import com.example.thermohammer.engine.VideoClip
import com.example.thermohammer.engine.VideoCodecResult
import com.example.thermohammer.engine.VideoResult
import com.example.thermohammer.engine.WebResult
import com.example.thermohammer.engine.cpu.CpuTest
import com.example.thermohammer.engine.cpu.CpuTestRun
import com.example.thermohammer.network.DeviceHammerStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class CsvExportTest {

    @Test fun fileNameIsKindDeviceSlugAndMinute() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("thermohammer-stress-honor-600-pro-20261112-1430.csv",
            CsvExport.fileName("stress", "HONOR 600 Pro", 1_794_441_600_000L + (14 * 60 + 30) * 60_000L, utc))
        assertEquals("thermohammer-benchmark-samsung-galaxy-s25-fe-20261112-0000.csv",
            CsvExport.fileName("benchmark", "Samsung Galaxy S25 FE", 1_794_441_600_000L, utc))
        assertEquals("device", CsvExport.slug("—"))
    }

    @Test fun ownStressRunHasTheSharedColumnsFirstThenAndroidsExtras() {
        val stamps = listOf(
            StampV2(tNs = 1_000_000_000L, ipsTotal = 2000, thermalStatus = 0, cpuTempMilliC = 41_250, battTempMilliC = 33_000,
                battCurrentUa = 1_234_000, battVoltageMv = 4100, freqRatioAvg = 0.95f, capRatioAvg = 1f, minCapRatio = 1f,
                schedFracAvg = 0.99f, envFlags = EnvFlag.CHARGING),
            StampV2(tNs = 2_000_000_000L, ipsTotal = 1500, thermalStatus = 2, attribution = Attribution.DVFS_CAP.code,
                gpuFps = 58.25, gpuFrameP95Ms = 19.5f, gpuFreqRatio = 0.8f))
        val lines = CsvExport.stress(stamps, 2000.0).trimEnd('\n').split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("elapsed_s,throughput,percent_of_baseline,thermal_state,"))
        assertEquals(CsvExport.STRESS_COLUMNS.size, lines[0].split(',').size)
        val first = lines[1].split(',')
        assertEquals(listOf("1.0", "2000", "100.0", "0", "41.3", "", "33.0", "1234.0", "4100"), first.take(9))
        assertEquals("1", first[CsvExport.STRESS_COLUMNS.indexOf("charging")])
        assertEquals("", first[CsvExport.STRESS_COLUMNS.indexOf("gpu_fps")])
        val second = lines[2].split(',')
        assertEquals("75.0", second[2])
        assertEquals("2", second[3])
        assertEquals("dvfs_cap", second[CsvExport.STRESS_COLUMNS.indexOf("attribution")])
        assertEquals("58.25", second[CsvExport.STRESS_COLUMNS.indexOf("gpu_fps")])
        assertTrue(lines.all { it.split(',').size == CsvExport.STRESS_COLUMNS.size })
    }

    @Test fun decimalsArePointsWhateverTheLanguage() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val csv = CsvExport.stress(listOf(StampV2(tNs = 1_500_000_000L, ipsTotal = 3, thermalStatus = 1)), 4.0)
            assertTrue(csv.lines()[1].startsWith("1.5,3,75.0,1,"))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test fun leaderboardStressRun() {
        val csv = CsvExport.leaderboardStress(listOf(DeviceHammerStamp(1000, 5000L, 0), DeviceHammerStamp(2500, 4200L, 3)))
        assertEquals("elapsed_s,score,thermal_state\n1.0,5000,0\n2.5,4200,3\n", csv)
    }

    @Test fun fieldsWithCommasOrQuotesAreQuoted() {
        assertEquals("plain", CsvExport.field("plain"))
        assertEquals("\"a,b\"", CsvExport.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.field("say \"hi\""))
    }

    @Test fun benchmarkHasEveryRawNumberThenTheScores() {
        val r = BenchmarkResult(
            tests = listOf(
                BenchmarkTestResult(BenchmarkTest.LAKE_VALLEY, avgFps = 121.37, lowFps = 98.2, frames = 5800,
                    stabilityPct = 91f, startTempC = 35f, endTempC = 42f, points = 6069),
                BenchmarkTestResult.unsupported(BenchmarkTest.NEON_CITY_RT)),
            gpuScore = 4056, gpuName = "Adreno (TM) 830", validityFlags = 0,
            ux = UxResult(web = WebResult(3700.0, 2050.0, 1200.0, 1340.0, 3230.0), scroll = null, gallery = null, photoMps = 118.3,
                video = VideoResult(listOf(VideoCodecResult(VideoClip.AVC_4K, 241.5, "c2.qti.avc.decoder", true))),
                export = null, qr = null, webPoints = 6000, scrollPoints = 0, galleryPoints = 0, photoPoints = 5915,
                videoPoints = 3019, exportPoints = 0, qrPoints = 0),
            cpu = CpuResult(CpuTest.entries.map { CpuTestRun(it, mapOf("MATRIX MULTIPLY" to CpuScore.ref(it))) }),
            mem = MemResult(RamResult(48.2, 39.5, 24.75, 151.3), StorageResult(2890.0, 1950.0, 58000.0, 47000.0, false, 512),
                DbResult(805.0, 790.0, 5100.0)),
            ai = AiResult(listOf(AiRun(AiTest.CLASSIFY, AiAccel.CPU, 845.04)), npuRuntime = null),
            llm = LlmResult(listOf(LlmRun(LlmAccel.CPU, 23.5, 6.8, threads = 6)), gpuName = null, freeGb = 7.25, largestModel = 8))
        val lines = CsvExport.benchmark(r).trimEnd('\n').split('\n')
        assertEquals("section,metric,value,unit", lines[0])
        assertTrue(lines.drop(1).all { it.split(',').size == 4 })
        for (row in listOf("cpu,math_points,6000,points", "cpu,math_matrix_multiply,1469.00,ops/s",
                "mem,ram_read,48.20,GB/s", "mem,ram_latency,151.30,ns", "mem,storage_direct_io,0,bool", "mem,db_query,5100.00,queries/s",
                "gpu,lake_valley_avg_fps,121.37,fps", "gpu,lake_valley_start_temp,35.0,C", "gpu,neon_city_rt_supported,0,bool",
                "ux,web_layout,3700.00,complexity", "ux,photo_edit,118.30,MP/s", "ux,video_h_264_4k_fps,241.50,fps",
                "ux,video_h_264_4k_hardware,1,bool", "ai,image_classification_cpu,845.04,inferences/s",
                "llm,cpu_prefill,23.50,tokens/s", "llm,cpu_threads,6,threads", "llm,largest_model,8,B parameters",
                "score,gpu,4056,points", "score,total,${r.total},points")) {
            assertTrue("missing $row", lines.contains(row))
        }
        assertFalse(lines.any { it.startsWith("gpu,neon_city_rt_avg_fps") })        // a test that couldn't run has no fps
        assertEquals("score,total,${r.total},points", lines.last())
    }
}
