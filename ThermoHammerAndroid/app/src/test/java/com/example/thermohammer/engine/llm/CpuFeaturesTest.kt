package com.example.thermohammer.engine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU feature words are the kernel's: every mask in these tests is built from <asm/hwcap.h>'s numbers, never read from the host. */
class CpuFeaturesTest {

    private fun bits(vararg n: Int): Long = n.fold(0L) { acc, b -> acc or (1L shl b) }

    // AT_HWCAP of a modern Arm core: fp asimd aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop
    // sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg
    private val modern = bits(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31)
    // a Cortex-A76 class core (Snapdragon 855): dot product and FP16, no i8mm, no SVE
    private val a76 = bits(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 20)
    // a Cortex-A53/A55-less old core: NEON, no FP16 arithmetic and no dot product
    private val old = bits(0, 1, 2, 3, 4, 5, 6, 7)

    @Test
    fun `the bits are the kernel's`() {
        assertEquals(1L shl 9, CpuFeatures.HWCAP_FPHP)
        assertEquals(1L shl 10, CpuFeatures.HWCAP_ASIMDHP)
        assertEquals(1L shl 20, CpuFeatures.HWCAP_ASIMDDP)
        assertEquals(1L shl 22, CpuFeatures.HWCAP_SVE)
        assertEquals(1L shl 1, CpuFeatures.HWCAP2_SVE2)
        assertEquals(1L shl 13, CpuFeatures.HWCAP2_I8MM)
        assertEquals(1L shl 14, CpuFeatures.HWCAP2_BF16)
        assertEquals(1L shl 23, CpuFeatures.HWCAP2_SME)
        assertEquals("SME2 sits above bit 31, in the 64-bit word", 137_438_953_472L, CpuFeatures.HWCAP2_SME2)
        assertEquals(8192L, CpuFeatures.HWCAP2_I8MM)
    }

    @Test
    fun `a Dimensity 9500 class CPU has everything and runs the i8mm build`() {
        val hwcap2 = bits(1, 9, 13, 14, 23, 37)               // sve2 svei8mm i8mm bf16 sme sme2
        assertTrue(CpuFeatures.supportsBaseKernels(modern))
        assertTrue(CpuFeatures.supportsI8mm(modern, hwcap2))
        val features = CpuFeatures.decode(modern, hwcap2).toMap()
        assertEquals(listOf("asimddp", "i8mm", "bf16", "fp16", "sve", "sve2", "sme", "sme2"), CpuFeatures.decode(modern, hwcap2).map { it.first })
        assertTrue(features.values.all { it })
    }

    @Test
    fun `a CPU with the dot product but no i8mm keeps the dot product library`() {
        assertTrue(CpuFeatures.supportsBaseKernels(a76))
        assertFalse(CpuFeatures.supportsI8mm(a76, 0L))
        assertFalse(CpuFeatures.supportsI8mm(a76, bits(0)))   // DCPODP alone is not i8mm
        val f = CpuFeatures.decode(a76, 0L).toMap()
        assertTrue(f.getValue("asimddp")); assertTrue(f.getValue("fp16"))
        assertFalse(f.getValue("i8mm")); assertFalse(f.getValue("sve")); assertFalse(f.getValue("sme2"))
    }

    @Test
    fun `a CPU without the dot product or FP16 runs neither build`() {
        assertFalse(CpuFeatures.supportsBaseKernels(old))
        assertFalse(CpuFeatures.supportsI8mm(old, bits(13)))   // i8mm reported but the base instructions aren't: still no
        assertFalse(CpuFeatures.supportsBaseKernels(a76 and (1L shl 9).inv()))        // no scalar FP16
        assertFalse(CpuFeatures.supportsBaseKernels(a76 and (1L shl 10).inv()))       // no vector FP16
        assertFalse(CpuFeatures.supportsBaseKernels(a76 and (1L shl 20).inv()))       // no dot product
        assertFalse(CpuFeatures.supportsBaseKernels(0L))
        assertFalse(CpuFeatures.supportsI8mm(0L, 0L))
    }

    @Test
    fun `fp16 needs both the scalar and the vector bit`() {
        assertFalse(CpuFeatures.decode(1L shl 9, 0L).toMap().getValue("fp16"))
        assertFalse(CpuFeatures.decode(1L shl 10, 0L).toMap().getValue("fp16"))
        assertTrue(CpuFeatures.decode((1L shl 9) or (1L shl 10), 0L).toMap().getValue("fp16"))
    }

    @Test
    fun `the report line lists every feature and both words in hex`() {
        val line = CpuFeatures.line(a76, bits(13, 37))
        assertTrue(line, line.startsWith("CPU features · asimddp yes · i8mm yes · bf16 no · fp16 yes · sve no · sve2 no · sme no · sme2 yes"))
        assertTrue(line, line.endsWith("AT_HWCAP 0x${a76.toString(16)} AT_HWCAP2 0x${bits(13, 37).toString(16)}"))
    }
}
