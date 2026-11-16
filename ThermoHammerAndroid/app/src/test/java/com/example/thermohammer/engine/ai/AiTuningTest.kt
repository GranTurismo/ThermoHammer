package com.example.thermohammer.engine.ai

import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTuningTest {

    private val gpuModels = AiSettings.GPU_MODELS
    private val ramp = ByteArray(256) { it.toByte() }
    /** An output unrelated to [ramp] (correlation about 0.01). */
    private val scrambled = ByteArray(256) { ((it * 37) % 251).toByte() }
    /** An output that is [ramp] three times out of four: off, but recognisably the same answer (correlation about 0.75). */
    private val drifted = ByteArray(256) { if (it % 4 != 0) ramp[it] else scrambled[it] }

    /** A fake probe: rates by (setting id, model), the same outputs unless `outputs` says otherwise, failures by `fail`. */
    private fun tune(
        rates: Map<Pair<String, AiTest>, Double>, outputs: (String, AiTest) -> List<ByteArray> = { _, _ -> listOf(ramp) },
        fail: (String, AiTest) -> String? = { _, _ -> null }, skip: (AiSetting) -> String? = { null },
        reference: (AiTest) -> List<ByteArray>? = { listOf(ramp) }, isActive: () -> Boolean = { true },
        deadlineNs: Long = Long.MAX_VALUE, clock: () -> Long = { 0L }, settings: List<AiSetting> = AiSettings.gpu(),
        calls: MutableList<String> = ArrayList()
    ) = AiTuner.tune(AiAccel.GPU, "Mali-G1-Ultra", settings, gpuModels, reference, skip, isActive, deadlineNs, clock) { test, s ->
        calls += "${s.id}/${test.name}"
        fail(s.id, test)?.let { throw IllegalStateException(it) }
        AiSample(rates.getValue(s.id to test), outputs(s.id, test), 0.05, 0.02)
    }

    private fun flat(default: Double, vararg overrides: Pair<String, Double>): Map<Pair<String, AiTest>, Double> {
        val all = AiSettings.gpu().associate { it.id to default }.toMutableMap()
        overrides.forEach { all[it.first] = it.second }
        return all.flatMap { (id, r) -> gpuModels.map { (id to it) to r } }.toMap()
    }

    @Test
    fun `settings only apply to GPUs that aren't Adreno and are known`() {
        assertTrue(AiSettings.gpuApplies("Mali-G1-Ultra MC12"))
        assertTrue(AiSettings.gpuApplies("Immortalis-G925"))
        assertTrue(AiSettings.gpuApplies("Xclipse 940"))
        assertFalse(AiSettings.gpuApplies("Adreno (TM) 830"))
        assertFalse(AiSettings.gpuApplies("QUALCOMM ADRENO(TM) 740"))
        assertFalse("an unknown renderer keeps the defaults", AiSettings.gpuApplies(""))
        assertFalse(AiSettings.gpuApplies("   "))
    }

    @Test
    fun `the default is first and the only one with no changes`() {
        for (list in listOf(AiSettings.gpu(), AiSettings.mediatekNpu())) {
            assertTrue(list.first().isDefault)
            assertEquals(1, list.count { it.isDefault })
            assertEquals(list.size, list.map { it.id }.toSet().size)
        }
        val d = AiSettings.DEFAULT
        assertEquals(GpuBackend.AUTOMATIC, d.gpuBackend); assertEquals(GpuStorage.DEFAULT, d.gpuStorage)
        assertEquals(GpuPrecision.DEFAULT, d.gpuPrecision)
        assertFalse(d.srcQuantized); assertNull(d.cpuThreads); assertFalse(d.gpuTakesRest)
    }

    @Test
    fun `each GPU setting changes exactly one thing`() {
        val changed = AiSettings.gpu().drop(1).map { s ->
            listOf(s.gpuBackend != GpuBackend.AUTOMATIC, s.gpuStorage != GpuStorage.DEFAULT, s.gpuPrecision != GpuPrecision.DEFAULT,
                s.srcQuantized, s.cpuThreads != null, s.gpuTakesRest).count { it }
        }
        assertEquals(List(changed.size) { 1 }, changed)
        assertTrue(AiSettings.gpu().any { it.gpuPrecision == GpuPrecision.FP16 })
    }

    @Test
    fun `correlation of identical, scaled and unrelated outputs`() {
        assertEquals(1.0, AiAgreement.correlation(ramp, ramp), 1e-12)
        assertEquals(1.0, AiAgreement.correlation(ramp, ByteArray(256) { (it / 2).toByte() }), 1e-3)
        assertEquals(-1.0, AiAgreement.correlation(ramp, ByteArray(256) { (255 - it).toByte() }), 1e-12)
        assertTrue(AiAgreement.correlation(ramp, scrambled) < 0.5)
        assertEquals("different sizes disagree", 0.0, AiAgreement.correlation(ramp, ByteArray(10)), 0.0)
        assertEquals("equal constants agree", 1.0, AiAgreement.correlation(ByteArray(8) { 5 }, ByteArray(8) { 5 }), 0.0)
        assertEquals("different constants don't", 0.0, AiAgreement.correlation(ByteArray(8) { 5 }, ByteArray(8) { 6 }), 0.0)
        assertEquals("bytes count as unsigned", 1.0, AiAgreement.correlation(byteArrayOf(-1, 0, -128), byteArrayOf(-1, 0, -128)), 1e-12)
    }

    @Test
    fun `detection compares boxes and scores but not the class ids`() {
        val classesA = ByteArray(256) { (it * 31 % 80).toByte() }
        val classesB = ByteArray(256) { (it * 17 % 80).toByte() }
        assertEquals(1.0, AiAgreement.of(AiTest.DETECT, listOf(ramp, ramp, classesA), listOf(ramp, ramp, classesB)), 1e-12)
        assertTrue(AiAgreement.of(AiTest.CLASSIFY, listOf(ramp, classesA), listOf(ramp, classesB)) < 0.9)
        assertEquals(0.0, AiAgreement.of(AiTest.DETECT, emptyList(), emptyList()), 0.0)
        assertEquals(0.0, AiAgreement.of(AiTest.DETECT, listOf(ramp), listOf(ramp, ramp)), 0.0)
    }

    @Test
    fun `a setting that is clearly faster on both models and agrees wins`() {
        val t = tune(flat(100.0, "opencl" to 130.0))
        assertEquals("opencl", t.chosen.id)
        assertEquals("CPU", t.reference)
    }

    @Test
    fun `a gain under five percent keeps the default`() {
        assertTrue(tune(flat(100.0, "opencl" to 104.0)).chosen.isDefault)
        assertEquals("opencl", tune(flat(100.0, "opencl" to 106.0)).chosen.id)
    }

    @Test
    fun `the fastest qualifying setting wins`() {
        val t = tune(flat(100.0, "opencl" to 120.0, "buffers" to 150.0, "textures" to 110.0))
        assertEquals("buffers", t.chosen.id)
    }

    @Test
    fun `faster on one model but slower on the other is rejected`() {
        val rates = flat(100.0).toMutableMap()
        rates["opengl" to AiTest.CLASSIFY] = 300.0            // 3× on one …
        rates["opengl" to AiTest.UPSCALE] = 85.0               // … and 15% slower on the other
        val t = tune(rates)
        assertTrue(t.chosen.isDefault)
        assertTrue(AiTuner.disqualified(t.rows.first { it.setting.id == "opengl" }, t.rows.first(), gpuModels)!!.startsWith("slower"))
    }

    @Test
    fun `outputs that disagree with the CPU's can't win however fast`() {
        val t = tune(flat(100.0, "int8-input" to 300.0), outputs = { id, _ -> listOf(if (id == "int8-input") scrambled else ramp) })
        assertTrue(t.chosen.isDefault)
        val row = t.rows.first { it.setting.id == "int8-input" }
        assertTrue(row.results.getValue(AiTest.CLASSIFY).agreement < 0.9)
        assertTrue(AiTuner.disqualified(row, t.rows.first(), gpuModels)!!.startsWith("outputs differ"))
    }

    @Test
    fun `when the default answers wrongly and nothing answers right the default stays - its runs will score 0`() {
        // every setting's output differs from the CPU's: none is better than another, and speed with a wrong answer is worth nothing
        val t = tune(flat(100.0, "opencl" to 130.0), outputs = { _, _ -> listOf(scrambled) })
        assertTrue(t.rows.first().results.getValue(AiTest.CLASSIFY).agreement < 0.5)
        assertTrue(AiTuner.defaultWrong(t.rows.first(), gpuModels))
        assertTrue(t.chosen.isDefault)
        assertTrue(AiTuner.lines(t)[1], AiTuner.lines(t)[1].endsWith("used: no setting's answers were the CPU's (wrong answers score 0)"))
    }

    @Test
    fun `when the default answers wrongly a slower setting that answers right is used`() {
        // the Xiaomi 11T's Mali-G77: OpenCL (the default) fast and wrong, OpenGL ES right at 0.84× and 0.69×
        val rates = flat(100.0).toMutableMap()
        rates["opengl" to AiTest.CLASSIFY] = 84.0
        rates["opengl" to AiTest.UPSCALE] = 69.0
        val calls = ArrayList<String>()
        val t = tune(rates, outputs = { id, _ -> listOf(if (id == "opengl") ramp else scrambled) }, calls = calls)
        assertEquals("opengl", t.chosen.id)
        assertTrue("a wrong default is no yardstick: the slower setting still gets its second model", calls.contains("opengl/${AiTest.UPSCALE.name}"))
        val lines = AiTuner.lines(t)
        assertTrue(lines.first(), lines.first().contains("scored with OpenGL ES backend"))
        assertTrue(lines[1], lines[1].endsWith("its answers differ from the CPU's: not used"))
        val settings = lines.drop(1)      // the first line is the heading, which names the setting used as well
        assertTrue(settings.first { it.contains("OpenGL ES backend") }.endsWith("· used"))
        assertTrue(settings.first { it.contains("buffer storage") }.endsWith("rejected: its answers differ from the CPU's too"))
    }

    @Test
    fun `of several settings that answer right where the default doesn't, the fastest is used`() {
        val t = tune(flat(100.0, "opengl" to 60.0, "textures" to 75.0),
            outputs = { id, _ -> listOf(if (id == "opengl" || id == "textures") ramp else scrambled) })
        assertEquals("textures", t.chosen.id)
        assertTrue(AiTuner.lines(t).drop(1).first { it.contains("OpenGL ES backend") }.endsWith("answers right, but another right setting was faster"))
    }

    @Test
    fun `an answer is wrong only when it has nothing in common with the CPU's`() {
        assertTrue(AiAgreement.isWrong(AiAgreement.correlation(ramp, scrambled)))          // the broken GPUs and NPUs: about 0
        val off = AiAgreement.correlation(ramp, drifted)
        assertTrue("$off", off > AiAgreement.WRONG_BELOW && off < AiTuner.MIN_AGREEMENT)     // a GPU's floating-point approximation: about 0.7
        assertFalse(AiAgreement.isWrong(off))
        assertFalse(AiAgreement.isWrong(1.0))
        assertTrue(AiAgreement.isWrong(0.49))
        assertFalse(AiAgreement.isWrong(0.5))
    }

    @Test
    fun `a default that is off but not wrong is judged for speed like any other`() {
        // nothing is zeroed for it, so nothing is searched for: the usual rule applies, and a merely faster setting can't replace an answer that agrees better
        val t = tune(flat(100.0, "opengl" to 60.0), outputs = { id, _ -> listOf(if (id == "opengl") ramp else drifted) })
        assertFalse(AiTuner.defaultWrong(t.rows.first(), gpuModels))
        assertTrue(t.chosen.isDefault)
    }

    @Test
    fun `where the default is wrong and nothing answers right a setting that is merely off is used`() {
        val t = tune(flat(100.0, "opengl" to 60.0), outputs = { id, _ -> listOf(if (id == "opengl") drifted else scrambled) })
        assertEquals("opengl", t.chosen.id)
        assertTrue(AiTuner.answersUsable(t.rows.first { it.setting.id == "opengl" }, gpuModels))
        assertFalse(AiTuner.answersRight(t.rows.first { it.setting.id == "opengl" }, gpuModels))
    }

    @Test
    fun `a setting that answers right is preferred to a faster one that is merely off`() {
        val t = tune(flat(100.0, "opengl" to 60.0, "textures" to 90.0),
            outputs = { id, _ -> listOf(when (id) { "opengl" -> ramp; "textures" -> drifted; else -> scrambled }) })
        assertEquals("opengl", t.chosen.id)
        assertTrue(AiTuner.lines(t).drop(1).first { it.contains("2D texture storage") }.endsWith("answers off but not wrong: another setting was used"))
    }

    @Test
    fun `a wrong answer's explanation says what is wrong, that it scores 0, and how fast it ran`() {
        assertEquals("wrong answer: its result doesn't match the CPU's (agreement %.2f, a match is 1.00), so it scores 0 · it ran at %.2f/s".format(0.02, 25.97),
            AiAgreement.wrongAnswer(25.97, 0.02))
    }

    @Test
    fun `without a CPU answer the outputs are compared with the default's`() {
        val t = tune(flat(100.0, "opencl" to 130.0, "buffers" to 140.0),
            outputs = { id, _ -> listOf(if (id == "buffers") scrambled else ramp) }, reference = { null })
        assertEquals("default", t.reference)
        assertEquals("opencl", t.chosen.id)
    }

    @Test
    fun `a failing or crashed setting is recorded and the rest carry on`() {
        val calls = ArrayList<String>()
        val t = tune(flat(100.0, "opencl" to 130.0, "buffers" to 140.0), calls = calls,
            fail = { id, _ -> if (id == "opengl") "OpenCL not supported" else null },
            skip = { if (it.id == "buffers") "crashed the app here last time" else null })
        assertEquals("opencl", t.chosen.id)
        val opengl = t.rows.first { it.setting.id == "opengl" }
        assertEquals("OpenCL not supported", opengl.results.getValue(AiTest.CLASSIFY).error)
        assertEquals("a failure stops that setting's remaining models", 1, opengl.results.size)
        assertEquals("crashed the app here last time", t.rows.first { it.setting.id == "buffers" }.skipped)
        assertFalse(calls.any { it.startsWith("buffers/") })
    }

    @Test
    fun `a setting far slower on the first model isn't tried on the second`() {
        val calls = ArrayList<String>()
        val t = tune(flat(100.0, "textures" to 40.0), calls = calls)
        assertFalse(calls.contains("textures/${AiTest.UPSCALE.name}"))
        assertTrue(t.rows.first { it.setting.id == "textures" }.skipped!!.startsWith("clearly slower"))
        assertTrue(calls.contains("opencl/${AiTest.UPSCALE.name}"))
    }

    @Test
    fun `the default always runs but no other setting starts after the deadline`() {
        var now = 0L
        val calls = ArrayList<String>()
        val t = AiTuner.tune(AiAccel.GPU, "Mali", AiSettings.gpu(), gpuModels, { listOf(ramp) }, { null }, { true }, deadlineNs = 100, nanoTime = { now }) { test, s ->
            calls += "${s.id}/${test.name}"
            now += 60
            AiSample(100.0, listOf(ramp))
        }
        assertEquals(listOf("default/CLASSIFY", "default/UPSCALE"), calls.take(2))
        assertTrue(t.rows.drop(1).all { it.skipped == "out of time" })
        assertTrue(t.chosen.isDefault)
    }

    @Test
    fun `a stopped run chooses the default`() {
        val t = tune(flat(100.0, "opencl" to 200.0), isActive = { false })
        assertTrue(t.chosen.isDefault)
    }

    @Test
    fun `a default that didn't run leaves nothing to beat`() {
        val t = tune(flat(100.0, "opencl" to 200.0), fail = { id, _ -> if (id == "default") "no GPU" else null })
        assertTrue(t.chosen.isDefault)
    }

    @Test
    fun `the report has a line per setting with rates, copies, agreement and the verdict`() {
        val t = tune(flat(100.0, "opencl" to 130.0, "opengl" to 50.0), fail = { id, _ -> if (id == "buffers") "driver error" else null })
            .copy(backend = "OpenGL ES (LiteRT found no usable OpenCL)")
        val lines = AiTuner.lines(t)
        assertTrue(lines.first(), lines.first().startsWith("AI tuning · GPU · Mali-G1-Ultra · scored with OpenCL backend"))
        assertTrue(lines.first().contains("default ran on OpenGL ES"))
        assertEquals(1 + AiSettings.gpu().size, lines.size)
        assertTrue(lines[1], lines[1].contains("classify 100.00/s (agreement 1.00, 10.00 ms/call, copy in 0.05 out 0.02 ms)"))
        assertTrue(lines[2], lines[2].contains("classify 130.00/s ×1.30") && lines[2].endsWith("used"))
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("buffer storage") && it.contains("failed (driver error)") && it.contains("rejected: failed: driver error") })
        assertTrue(lines.any { it.contains("OpenGL ES backend") && it.contains("not tried: clearly slower on classify") })
    }

    @Test
    fun `NPU delegation lines say what stayed on the CPU`() {
        val d = AiDelegation(AiTest.DETECT, 309, 317, 3, listOf("ARG_MAX", "EXP"))
        assertEquals(listOf("AI 2 OBJECT DETECTION · NPU took 309 of 317 ops in 3 partitions · not on the NPU: ARG_MAX, EXP"),
            AiTuner.delegationLines(listOf(d)))
        assertEquals(listOf("AI 6 SOUND RECOGNITION · NPU took 32 of 32 ops in 1 partition"),
            AiTuner.delegationLines(listOf(AiDelegation(AiTest.SOUND, 32, 32, 1, emptyList()))))
    }

    // ── LiteRT's log ─────────────────────────────────────────────────────────

    @Test
    fun `delegation comes from LiteRT's partition line and its unlegalized ops`() {
        val lines = listOf(
            "I/litert: [third_party/odml/litert/litert/vendors/mediatek/compiler/compiler_plugin.cc:512] Use Get supported operations for partition.",
            "E/litert: [third_party/odml/litert/litert/vendors/mediatek/compiler/compiler_plugin.cc:518] Unlegalized op: 56",
            "E/litert: [third_party/odml/litert/litert/vendors/mediatek/compiler/compiler_plugin.cc:518] Unlegalized op: 47",
            "E/litert: [third_party/odml/litert/litert/vendors/mediatek/compiler/compiler_plugin.cc:518] Unlegalized op: 56",
            "I/litert: [third_party/odml/litert/litert/compiler/plugin/compiler_plugin.cc:739] Partitioned subgraph<0>, selected 309 ops, from a total of 317 ops. resulted in 3 partitions.")
        val d = LiteRtLog.delegation(AiTest.DETECT, lines)!!
        assertEquals(309, d.selected); assertEquals(317, d.total); assertEquals(3, d.partitions)
        assertEquals(listOf("ARG_MAX", "EXP"), d.notOnNpu)
        assertNull("nothing logged, nothing reported", LiteRtLog.delegation(AiTest.DETECT, listOf("I/litert: something else")))
        assertEquals("op 999", LiteRtLog.opName(999))
        assertEquals("RESIZE_NEAREST_NEIGHBOR", LiteRtLog.opName(97))
    }

    @Test
    fun `every op LiteRT can name comes out by name, whichever plugin names it`() {
        // the numbers are TFLite's BuiltinOperator values (schema.fbs)
        assertEquals("ADD", LiteRtLog.opName(0)); assertEquals("CONV_2D", LiteRtLog.opName(3)); assertEquals("EXP", LiteRtLog.opName(47))
        assertEquals("CAST", LiteRtLog.opName(53)); assertEquals("ARG_MAX", LiteRtLog.opName(56)); assertEquals("RSQRT", LiteRtLog.opName(76))
        assertEquals("REDUCE_MAX", LiteRtLog.opName(82)); assertEquals("RESIZE_NEAREST_NEIGHBOR", LiteRtLog.opName(97))
        assertEquals("QUANTIZE", LiteRtLog.opName(114)); assertEquals("BATCH_MATMUL", LiteRtLog.opName(126)); assertEquals("GELU", LiteRtLog.opName(150))
        assertEquals("op 9999", LiteRtLog.opName(9999)); assertEquals("op -1", LiteRtLog.opName(-1))
    }

    @Test
    fun `ops the plugin won't take at all are named too, and a model with ops but no partition line is flagged`() {
        val unsupported = listOf("E/litert: [x.cc:1] Unsupported op: 53", "E/litert: [x.cc:2] Unlegalized op: 47")
        val d = LiteRtLog.delegation(AiTest.DETECT, unsupported)!!
        assertFalse("no partition line was logged", d.logged)
        assertEquals(listOf("CAST", "EXP"), d.notOnNpu)
        assertEquals(listOf("AI 2 OBJECT DETECTION · NPU delegation unknown: LiteRT's log had no \"Partitioned subgraph\" line for this model · ops its plugin couldn't legalize: CAST, EXP"),
            AiTuner.delegationLines(listOf(d)))
        // a model LiteRT logged nothing for: the caller's placeholder says so rather than staying silent
        assertEquals(listOf("AI 6 SOUND RECOGNITION · NPU delegation unknown: LiteRT's log had no \"Partitioned subgraph\" line for this model"),
            AiTuner.delegationLines(listOf(AiDelegation(AiTest.SOUND, 0, 0, 0, emptyList(), logged = false))))
    }

    @Test
    fun `ops that stayed on the CPU without a name are still counted`() {
        val lines = listOf("I/litert: [x.cc:739] Partitioned subgraph<0>, selected 300 ops, from a total of 317 ops. resulted in 2 partitions.")
        assertEquals(listOf("AI 2 OBJECT DETECTION · NPU took 300 of 317 ops in 2 partitions · 17 ops stayed on the CPU (LiteRT named none)"),
            AiTuner.delegationLines(listOf(LiteRtLog.delegation(AiTest.DETECT, lines)!!)))
    }

    // ── the CPU's settings ──────────────────────────────────────────────────────

    private val cpuModels = AiSettings.CPU_MODELS
    private val fast = listOf(7, 4, 5, 6)                  // a Dimensity 9500's Ultra and Premium cores, fastest first

    @Test
    fun `a phone with fast cores and ADPF offers four CPU settings, the default first`() {
        val list = AiSettings.cpu(fast, totalCores = 8, adpf = true)
        assertEquals(listOf("default", "pin-fast", "adpf", "pin-fast+adpf"), list.map { it.id })
        assertTrue(list.first().isDefault)
        assertEquals(fast, list[1].cpuPin); assertFalse(list[1].adpf)
        assertTrue(list[2].cpuPin.isEmpty()); assertTrue(list[2].adpf)
        assertEquals(fast, list[3].cpuPin); assertTrue(list[3].adpf)
        assertTrue(list[1].label, list[1].label.contains("7,4,5,6"))
        assertEquals(list.size, list.map { it.id }.toSet().size)
    }

    @Test
    fun `the cores for pinning are the four fastest, and only where there is something to choose from`() {
        assertEquals(fast, AiSettings.cpu(listOf(7, 4, 5, 6, 0, 1), 8, adpf = false)[1].cpuPin)
        assertEquals(listOf("default", "pin-fast"), AiSettings.cpu(fast, 8, adpf = false).map { it.id })
        assertEquals("fewer fast cores than threads: pinning would pile threads on a core", listOf("default", "adpf"), AiSettings.cpu(listOf(7, 4, 5), 8, true).map { it.id })
        assertEquals("a four-core phone has no cores to leave out", listOf("default", "adpf"), AiSettings.cpu(listOf(0, 1, 2, 3), 4, true).map { it.id })
        assertEquals("unknown topology, no ADPF: nothing to try", emptyList<AiSetting>(), AiSettings.cpu(emptyList(), 8, false))
        assertEquals(listOf("default", "adpf"), AiSettings.cpu(emptyList(), 8, true).map { it.id })
    }

    @Test
    fun `each CPU setting changes one thing or the two together, and none touches the accelerators' options`() {
        for (s in AiSettings.cpu(fast, 8, true).drop(1)) {
            assertEquals(GpuBackend.AUTOMATIC, s.gpuBackend); assertFalse(s.srcQuantized); assertNull(s.cpuThreads); assertFalse(s.gpuTakesRest)
            assertTrue(s.cpuPin.isNotEmpty() || s.adpf)
        }
    }

    private fun cpuTune(rates: Map<String, Double>, outputs: (String) -> List<ByteArray> = { listOf(ramp) },
                        fail: (String) -> String? = { null }, calls: MutableList<String> = ArrayList()) =
        AiTuner.tune(AiAccel.CPU, "CPU · fast cores 7,4,5,6 first", AiSettings.cpu(fast, 8, true), cpuModels, { null }, { null }, { true },
            Long.MAX_VALUE) { test, s ->
            calls += "${s.id}/${test.name}"
            fail(s.id)?.let { throw IllegalStateException(it) }
            AiSample(rates.getValue(s.id), outputs(s.id), note = if (s.cpuPin.isNotEmpty()) "LiteRT started 3 threads: 3 of 3 inside the asked cores cpu4-7 (masks cpu4-7 ×3)" else null)
        }

    @Test
    fun `a CPU setting clearly faster on both models and agreeing with the default's outputs is adopted`() {
        val t = cpuTune(mapOf("default" to 100.0, "pin-fast" to 118.0, "adpf" to 103.0, "pin-fast+adpf" to 112.0))
        assertEquals("pin-fast", t.chosen.id)
        assertEquals("with no CPU reference the outputs are compared with the default's", "default", t.reference)
        assertEquals(AiAccel.CPU, t.accel)
    }

    @Test
    fun `a CPU setting under five percent faster, or slower, keeps the default`() {
        assertTrue(cpuTune(mapOf("default" to 100.0, "pin-fast" to 104.0, "adpf" to 100.0, "pin-fast+adpf" to 101.0)).chosen.isDefault)
        assertTrue(cpuTune(mapOf("default" to 100.0, "pin-fast" to 80.0, "adpf" to 90.0, "pin-fast+adpf" to 85.0)).chosen.isDefault)
    }

    @Test
    fun `an ADPF setting that fails to start is rejected with its reason and the others still run`() {
        val t = cpuTune(mapOf("default" to 100.0, "pin-fast" to 120.0, "adpf" to 0.0, "pin-fast+adpf" to 0.0),
            fail = { if (it.contains("adpf")) "the hint session was refused: no governor" else null })
        assertEquals("pin-fast", t.chosen.id)
        val lines = AiTuner.lines(t)
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("performance hint session") && it.contains("rejected: failed: the hint session was refused: no governor") })
    }

    @Test
    fun `the report says where LiteRT's threads ended up for a pinned CPU setting`() {
        val t = cpuTune(mapOf("default" to 100.0, "pin-fast" to 120.0, "adpf" to 100.0, "pin-fast+adpf" to 100.0))
        val lines = AiTuner.lines(t)
        assertTrue(lines[0], lines[0].startsWith("AI tuning · CPU · CPU · fast cores 7,4,5,6 first · scored with threads on the 4 fastest cores (7,4,5,6)"))
        val pinned = lines.first { it.contains("threads on the 4 fastest cores") && !it.startsWith("AI tuning") }
        assertTrue(pinned, pinned.contains("LiteRT started 3 threads: 3 of 3 inside the asked cores cpu4-7") && pinned.endsWith("(masks cpu4-7 ×3)"))
        assertTrue("the default row has no note", lines.first { it.startsWith("  default") }.let { !it.contains("LiteRT started") })
    }

    @Test
    fun `the default is still first and alone in having no changes in every list including the CPU's`() {
        for (list in listOf(AiSettings.gpu(), AiSettings.mediatekNpu(), AiSettings.cpu(fast, 8, true))) {
            assertTrue(list.first().isDefault); assertEquals(1, list.count { it.isDefault })
        }
        assertTrue(AiSettings.DEFAULT.cpuPin.isEmpty()); assertFalse(AiSettings.DEFAULT.adpf)
    }

    @Test
    fun `the GPU backend is OpenGL only when LiteRT says OpenCL isn't usable`() {
        assertEquals("OpenCL", LiteRtLog.gpuBackend(emptyList()))
        assertNotNull(LiteRtLog.gpuBackend(listOf("I/litert: [x.cc:1] OpenCL not supported on this platform. Using OpenGL instead.")).takeIf { it.startsWith("OpenGL ES") })
    }
}
