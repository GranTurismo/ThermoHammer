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
        assertFalse(d.srcQuantized); assertNull(d.cpuThreads); assertFalse(d.gpuTakesRest)
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
    fun `when the default itself agrees poorly a setting only has to be as good`() {
        // an output the default and the CPU already differ on: a setting that differs just as much is no worse
        val t = tune(flat(100.0, "opencl" to 130.0), outputs = { _, _ -> listOf(scrambled) })
        assertTrue(t.rows.first().results.getValue(AiTest.CLASSIFY).agreement < 0.5)
        assertEquals("opencl", t.chosen.id)
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
    fun `the GPU backend is OpenGL only when LiteRT says OpenCL isn't usable`() {
        assertEquals("OpenCL", LiteRtLog.gpuBackend(emptyList()))
        assertNotNull(LiteRtLog.gpuBackend(listOf("I/litert: [x.cc:1] OpenCL not supported on this platform. Using OpenGL instead.")).takeIf { it.startsWith("OpenGL ES") })
    }
}
