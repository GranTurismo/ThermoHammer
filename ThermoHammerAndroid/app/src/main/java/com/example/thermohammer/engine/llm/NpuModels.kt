package com.example.thermohammer.engine.llm

import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
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
 * phone gets only its own chip's file. Other chips get the same model built by ThermoHammer with the chip makers'
 * compilers (tools/npu_models), downloaded from the site (NpuServerModels). A chip no compiler targets runs the LLM
 * test on the CPU and the GPU, and its NPU third counts 0.
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
    /** In this release's Play AI pack. */
    fun inPack(soc: String = soc()): Boolean = soc in CHIPS
    /** The chip has an NPU model: in the Play pack, or listed by the site (NpuServerModels — a chip added since this release). */
    fun supported(context: Context, soc: String = soc()): Boolean =
        soc.isNotBlank() && (inPack(soc) || NpuServerModels.entry(context, soc) != null)
    fun fileName(soc: String): String = "Gemma3-1B-IT_q4_ekv1280_$soc.litertlm"

    /** Download size for the checklist: a site file's own, else Google's (Snapdragon ~690 MB, Dimensity ~1,030 MB). */
    fun sizeMb(context: Context, soc: String = soc()): Int =
        NpuServerModels.entry(context, soc)?.takeIf { !inPack(soc) }?.let { (it.size / 1_000_000).toInt() }
            ?: if (soc.startsWith("mt")) 1030 else 690

    /**
     * Test builds, report only: other builds of the same model for this chip, in [testDir]/compare/ (adb push) — e.g. our
     * own tools/npu_models build next to Google's — each run after the scored one, with the same task and answer check.
     */
    fun compareTestFiles(context: Context): List<File> =
        if (com.example.thermohammer.TestBuild.PROBES)
            File(testDir(context), "compare").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".litertlm") }.sortedBy { it.name }
        else emptyList()

    /** Test builds' own folder (NpuModelImportActivity, adb): a sideloaded copy gets no AI pack. */
    fun testDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "npu-llm").apply { mkdirs() }

    /** The file Play delivered (the pack's gemma/ folder), or null. Never cached: Play can move or remove the pack. */
    private fun packFile(context: Context, soc: String): File? {
        val assets = runCatching { AiPackManagerFactory.getInstance(context).getPackLocation(PACK)?.assetsPath() }
            .getOrNull() ?: return null
        return File(assets, "gemma/${fileName(soc)}").takeIf { it.isFile }
    }

    /** The model for this phone's chip — from Play, from the site, or in a test build from its own folder — or null. */
    fun modelFile(context: Context): File? {
        val soc = soc()
        if (soc.isBlank()) return null
        if (inPack(soc)) packFile(context, soc)?.let { return it }
        NpuServerModels.file(context, soc)?.let { return it }
        if (com.example.thermohammer.TestBuild.PROBES)
            return testDir(context).listFiles().orEmpty()
                .filter { it.name.endsWith(".litertlm") && soc in it.name.lowercase() }.maxByOrNull { it.lastModified() }
        return null
    }

    /**
     * MainActivity's launcher for Play's "download over mobile data?" dialog: Play holds a large pack until the user says
     * yes (REQUIRES_USER_CONFIRMATION, WAITING_FOR_WIFI). Asked once per app session.
     */
    @Volatile var confirmLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    @Volatile private var confirmAsked = false

    sealed interface Status {
        /** No Gemma NPU file exists for this chip (Play's pack nor the site): nothing to wait for, the NPU third counts 0. */
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
        if (!supported(context, soc)) return Status.NotNeeded
        modelFile(context)?.let { return Status.Ready(it) }
        // a chip added since this release: its file comes from the site, not Play
        if (!inPack(soc)) {
            val entry = NpuServerModels.entry(context, soc) ?: return Status.NotNeeded
            if (fetch) NpuServerModels.startDownload(context, entry)
            return when {
                NpuServerModels.isRunning() -> Status.Downloading(NpuServerModels.progress.coerceAtLeast(0))
                NpuServerModels.waitingForWifi -> Status.WaitingForWifi
                NpuServerModels.lastError != null -> Status.Unavailable(NpuServerModels.lastError!!)
                else -> Status.Unavailable("not downloaded yet")
            }
        }
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
            // the download itself (transferProgressPercentage is only Play's last copy into the app's storage)
            AiPackStatus.PENDING, AiPackStatus.DOWNLOADING, AiPackStatus.TRANSFERRING -> Status.Downloading(
                if (state.totalBytesToDownload() > 0) (state.bytesDownloaded() * 100 / state.totalBytesToDownload()).toInt().coerceIn(0, 100)
                else state.transferProgressPercentage().coerceIn(0, 100))
            AiPackStatus.WAITING_FOR_WIFI, AiPackStatus.REQUIRES_USER_CONFIRMATION -> {
                // Play waits for the user's OK (or Wi-Fi): show its dialog once, from the activity
                val launcher = confirmLauncher
                if (launcher != null && !confirmAsked) {
                    confirmAsked = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runCatching { manager.showConfirmationDialog(launcher) } }
                }
                Status.WaitingForWifi
            }
            AiPackStatus.NOT_INSTALLED -> Status.Unavailable("not downloaded yet")
            AiPackStatus.FAILED -> Status.Unavailable("the download failed (error ${state.errorCode()})")
            AiPackStatus.CANCELED -> Status.Unavailable("the download was cancelled")
            else -> Status.Unavailable("Google Play reports status ${state.status()}")
        }
    }
}
