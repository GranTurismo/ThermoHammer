package com.example.thermohammer.engine.llm

/**
 * What this CPU can do, from the two words the Linux kernel hands every process (getauxval AT_HWCAP and AT_HWCAP2).
 * A bit is set only when every core has the feature, so code built for it can't hit an instruction some core lacks.
 * The bit numbers are the kernel's own (<asm/hwcap.h>, copied from NDK 28's aarch64-linux-android sysroot); the
 * values come from native code (LlmNative.hwcaps), so the rules here run on the JVM with any mask injected.
 *
 * The LLM test's CPU kernels are built for the Arm dot product and FP16; a second build also uses i8mm (8-bit matrix
 * multiply, Armv8.6), and is opened only where [supportsI8mm] says so.
 */
object CpuFeatures {
    // AT_HWCAP
    const val HWCAP_FPHP = 1L shl 9            // scalar FP16
    const val HWCAP_ASIMDHP = 1L shl 10        // vector FP16
    const val HWCAP_ASIMDDP = 1L shl 20        // dot product (SDOT/UDOT)
    const val HWCAP_SVE = 1L shl 22
    // AT_HWCAP2
    const val HWCAP2_SVE2 = 1L shl 1
    const val HWCAP2_I8MM = 1L shl 13          // SMMLA/UMMLA/USMMLA/USDOT
    const val HWCAP2_BF16 = 1L shl 14
    const val HWCAP2_SME = 1L shl 23
    const val HWCAP2_SME2 = 1L shl 37

    /** The features the report lists, in order, with the word and bit each comes from. */
    private class Spec(val name: String, val second: Boolean, val mask: Long)

    private val SPECS = listOf(
        Spec("asimddp", false, HWCAP_ASIMDDP), Spec("i8mm", true, HWCAP2_I8MM), Spec("bf16", true, HWCAP2_BF16),
        Spec("sve", false, HWCAP_SVE), Spec("sve2", true, HWCAP2_SVE2), Spec("sme", true, HWCAP2_SME), Spec("sme2", true, HWCAP2_SME2))

    /** Whether the CPU has the dot product and FP16 vector arithmetic every LLM CPU kernel is built with. */
    fun supportsBaseKernels(hwcap: Long): Boolean =
        (hwcap and (HWCAP_FPHP or HWCAP_ASIMDHP or HWCAP_ASIMDDP)) == (HWCAP_FPHP or HWCAP_ASIMDHP or HWCAP_ASIMDDP)

    /** Whether the i8mm build of the kernels may run: the base kernels' instructions, and i8mm on top. */
    fun supportsI8mm(hwcap: Long, hwcap2: Long): Boolean = supportsBaseKernels(hwcap) && (hwcap2 and HWCAP2_I8MM) != 0L

    /** Feature name → present, in report order; "fp16" is true when both scalar and vector FP16 are. */
    fun decode(hwcap: Long, hwcap2: Long): List<Pair<String, Boolean>> {
        val fp16 = (hwcap and (HWCAP_FPHP or HWCAP_ASIMDHP)) == (HWCAP_FPHP or HWCAP_ASIMDHP)
        val found = SPECS.map { it.name to (((if (it.second) hwcap2 else hwcap) and it.mask) != 0L) }
        return found.take(3) + ("fp16" to fp16) + found.drop(3)
    }

    /** "CPU features · asimddp yes · i8mm yes · … · AT_HWCAP 0x… AT_HWCAP2 0x…" */
    fun line(hwcap: Long, hwcap2: Long): String =
        "CPU features · " + decode(hwcap, hwcap2).joinToString(" · ") { "${it.first} ${if (it.second) "yes" else "no"}" } +
            " · AT_HWCAP 0x%x AT_HWCAP2 0x%x".format(hwcap, hwcap2)
}
