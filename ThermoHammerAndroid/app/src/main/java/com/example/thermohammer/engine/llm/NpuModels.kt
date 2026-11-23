package com.example.thermohammer.engine.llm

import android.content.Context
import android.os.Build
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.AiPackStates
import com.google.android.play.core.aipacks.model.AiPackStatus
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The LLM test's NPU model (version 5): Google's Gemma 3 1B build for the phone's chip, delivered by Play as the
 * fast-follow AI pack `gemma_npu` (ai/gemma_npu/build.gradle.kts) — Play downloads it right after the install, and a
 * phone gets only its own chip's file. Chips Google builds no such file for run the LLM test on the CPU and the GPU,
 * and its NPU third counts 0.
 *
 * A supported chip without its file (the download hasn't finished, or a copy that didn't come from Play) can't give the
 * NPU third: that run is incomplete (ValidityFlag.NPU_MODEL_MISSING) and can't be submitted — it would be scored as if
 * the phone had no NPU.
 */
object NpuModels {
    const val PACK = "gemma_npu"

    /**
     * The chips with a Gemma 3 1B NPU file at 4 bits (huggingface.co/litert-community/Gemma3-1B-IT): Build.SOC_MODEL in
     * lower case. Google Tensor's files are 8-bit (twice the work per token), so they aren't used. Repeated in
     * ai/gemma_npu/build.gradle.kts and in the server's BenchmarkScoring.cs.
     */
    val CHIPS = setOf("sm8550", "sm8650", "sm8750", "sm8850", "mt6989", "mt6991", "mt6993")

    fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.lowercase() else ""
    fun supported(soc: String = soc()): Boolean = soc in CHIPS
    fun fileName(soc: String): String = "Gemma3-1B-IT_q4_ekv1280_$soc.litertlm"

    /** Rough download size for the checklist: Snapdragon files are ~690 MB, Dimensity ~1,030 MB. */
    fun sizeMb(soc: String = soc()): Int = if (soc.startsWith("mt")) 1030 else 690

    /** Test builds' own folder (NpuModelImportActivity, adb): a sideloaded copy gets no AI pack. */
    fun testDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "npu-llm").apply { mkdirs() }

    /** The file Play delivered (the pack's gemma/ folder), or null. Never cached: Play can move or remove the pack. */
    private fun packFile(context: Context, soc: String): File? {
        val assets = runCatching { AiPackManagerFactory.getInstance(context).getPackLocation(PACK)?.assetsPath() }
            .getOrNull() ?: return null
        return File(assets, "gemma/${fileName(soc)}").takeIf { it.isFile }
    }

    /** The model for this phone's chip — from Play, or in a test build from its own folder — or null. */
    fun modelFile(context: Context): File? {
        val soc = soc()
        if (soc.isBlank()) return null
        if (supported(soc)) packFile(context, soc)?.let { return it }
        if (com.example.thermohammer.TestBuild.PROBES)
            return testDir(context).listFiles().orEmpty()
                .filter { it.name.endsWith(".litertlm") && soc in it.name.lowercase() }.maxByOrNull { it.lastModified() }
        return null
    }

    sealed interface Status {
        /** Google builds no Gemma NPU file for this chip: nothing to wait for, the NPU third counts 0. */
        data object NotNeeded : Status
        data class Ready(val file: File) : Status
        data class Downloading(val percent: Int) : Status
        /** Play holds a large download for Wi-Fi, or for the user's OK on mobile data. */
        data object WaitingForWifi : Status
        data class Unavailable(val why: String) : Status
    }

    /** Where the model stands. With `fetch`, asks Play to download it if it isn't on the phone (harmless when it is). */
    suspend fun status(context: Context, fetch: Boolean = false): Status {
        val soc = soc()
        if (!supported(soc)) return Status.NotNeeded
        modelFile(context)?.let { return Status.Ready(it) }
        val manager = runCatching { AiPackManagerFactory.getInstance(context) }.getOrNull()
            ?: return Status.Unavailable("Google Play isn't available")
        val task = runCatching { if (fetch) manager.fetch(listOf(PACK)) else manager.getPackStates(listOf(PACK)) }
            .getOrElse { return Status.Unavailable(it.message ?: "Google Play couldn't be asked") }
        val states: AiPackStates = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                task.addOnCompleteListener { t -> cont.resume(if (t.isSuccessful) t.result else null) }
            }
        } ?: return Status.Unavailable(if (fetch) "Google Play didn't start the download (installed from Google Play?)"
            else "Google Play gave no answer")
        val state = states.packStates()[PACK] ?: return Status.Unavailable("Google Play doesn't know the model")
        return when (state.status()) {
            AiPackStatus.COMPLETED -> modelFile(context)?.let { Status.Ready(it) }
                ?: Status.Unavailable("downloaded, but the file for ${soc.uppercase()} isn't in it")
            AiPackStatus.PENDING, AiPackStatus.DOWNLOADING, AiPackStatus.TRANSFERRING ->
                Status.Downloading(state.transferProgressPercentage().coerceIn(0, 100))
            AiPackStatus.WAITING_FOR_WIFI, AiPackStatus.REQUIRES_USER_CONFIRMATION -> Status.WaitingForWifi
            AiPackStatus.NOT_INSTALLED -> Status.Unavailable("not downloaded yet")
            AiPackStatus.FAILED -> Status.Unavailable("the download failed (error ${state.errorCode()})")
            AiPackStatus.CANCELED -> Status.Unavailable("the download was cancelled")
            else -> Status.Unavailable("Google Play reports status ${state.status()}")
        }
    }
}
