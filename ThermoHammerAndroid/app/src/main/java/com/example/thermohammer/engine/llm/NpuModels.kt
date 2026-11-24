package com.example.thermohammer.engine.llm

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Test builds' NPU LLM run (report only: scoring version 5 doesn't score the NPU — no way to run it reaches every chip
 * yet, BENCHMARK_V5_PLAN.md). Its model, Gemma 3 1B compiled for the phone's chip: a copy in the test folder (adb push,
 * NpuModelImportActivity), else one the site lists for the chip (NpuServerModels, built by tools/npu_models).
 * Play builds never call this.
 */
object NpuModels {
    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.lowercase() else ""
    fun fileName(soc: String): String = "Gemma3-1B-IT_q4_ekv1280_$soc.litertlm"

    /** A model for this chip exists: in the test folder or on the site. */
    fun supported(context: Context, soc: String = soc()): Boolean =
        soc.isNotBlank() && (testFile(context, soc) != null || NpuServerModels.entry(context, soc) != null)

    /** Download size for the checklist: the site's file, else Google's (Snapdragon ~690 MB, Dimensity ~1,030 MB). */
    fun sizeMb(context: Context, soc: String = soc()): Int =
        NpuServerModels.entry(context, soc)?.let { (it.size / 1_000_000).toInt() } ?: if (soc.startsWith("mt")) 1030 else 690

    /**
     * Other builds of the same model for this chip, in [testDir]/compare/ (adb push) — e.g. our own tools/npu_models build
     * next to Google's — each run after the first one, with the same task and answer check.
     */
    fun compareTestFiles(context: Context): List<File> =
        if (com.example.thermohammer.TestBuild.PROBES)
            File(testDir(context), "compare").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".litertlm") }.sortedBy { it.name }
        else emptyList()

    /** The test folder (NpuModelImportActivity, adb). */
    fun testDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "npu-llm").apply { mkdirs() }

    private fun testFile(context: Context, soc: String): File? =
        if (!com.example.thermohammer.TestBuild.PROBES) null
        else testDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".litertlm") && soc in it.name.lowercase() }.maxByOrNull { it.lastModified() }

    /** The model for this phone's chip — the test folder's copy first, then the site's — or null. */
    fun modelFile(context: Context): File? {
        val soc = soc()
        if (soc.isBlank()) return null
        return testFile(context, soc) ?: NpuServerModels.file(context, soc)
    }

    sealed interface Status {
        /** No model for this chip. */
        data object NotNeeded : Status
        data class Ready(val file: File) : Status
        data class Downloading(val percent: Int) : Status
        data object WaitingForWifi : Status
        data class Unavailable(val why: String) : Status
    }

    /** Where the model stands. With `fetch`, starts the site download if it isn't on the phone. */
    fun status(context: Context, fetch: Boolean = false): Status {
        val soc = soc()
        if (!supported(context, soc)) return Status.NotNeeded
        modelFile(context)?.let { return Status.Ready(it) }
        val entry = NpuServerModels.entry(context, soc) ?: return Status.NotNeeded
        if (fetch) NpuServerModels.startDownload(context, entry)
        return when {
            NpuServerModels.isRunning() -> Status.Downloading(NpuServerModels.progress.coerceAtLeast(0))
            NpuServerModels.waitingForWifi -> Status.WaitingForWifi
            NpuServerModels.lastError != null -> Status.Unavailable(NpuServerModels.lastError!!)
            else -> Status.Unavailable("not downloaded yet")
        }
    }
}
