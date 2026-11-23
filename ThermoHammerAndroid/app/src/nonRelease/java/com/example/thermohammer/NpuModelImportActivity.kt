package com.example.thermohammer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.example.thermohammer.engine.llm.NpuLlmProbe
import com.example.thermohammer.engine.llm.NpuLlmProbeService
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

/**
 * Test builds only: "Open with ThermoHammer Bench" (or Share) for a LiteRT-LM model file — copied into the NPU LLM
 * engine's test folder (NpuModels.testDir). A file that isn't a .litertlm is refused. No UI beyond a toast.
 */
class NpuModelImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the NPU probe alone, without a whole benchmark (its lines go to logcat, tag NpuLlmProbe):
        // adb shell am start -n <package>/com.example.thermohammer.NpuModelImportActivity -a com.example.thermohammer.RUN_NPU_LLM_PROBE
        // — this activity is exported and in the foreground, so it may start the probe's unexported service
        if (intent?.action == NpuLlmProbeService.ACTION_RUN) {
            // extra "file": a model's path to run instead of the chip's own (e.g. a generic one, compiled on the device)
            val started = runCatching { startService(Intent(this, NpuLlmProbeService::class.java).setAction(NpuLlmProbeService.ACTION_RUN)
                .apply { intent.getStringExtra("file")?.let { putExtra("file", it) } }) }
            Toast.makeText(this, if (started.isSuccess) "NPU LLM probe started (logcat: NpuLlmProbe)"
                else "NPU LLM probe not started: ${started.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }
        // the GPU AI diagnosis (engine/ai/AiGpuDiagnosis.kt), the same way; extra "only" = e.g. "classify,upscale"
        val diag = com.example.thermohammer.engine.ai.AiGpuDiagnosisService
        // the LLM CPU tuning check in the app's own process (a foreground process may use every core; the diagnosis
        // process is a background one, which Android keeps off the fastest cores)
        // the AI section's GPU compile check (DriverCheck.gpu) alone, from the app's process as the benchmark calls it
        if (intent?.action == diag.ACTION_GEMMA && intent.getStringExtra("mode") == "gpucheck") {
            val app = applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val failed = HashMap<com.example.thermohammer.engine.AiTest, String>()
                val outcome = com.example.thermohammer.engine.DriverCheck.gpu(app, intent.getStringExtra("setting") ?: "opengl",
                    com.example.thermohammer.engine.AiTest.entries, failed) { t -> android.util.Log.e("GpuCheck", "checking ${t.name}") }
                android.util.Log.e("GpuCheck", "outcome $outcome · failed ${failed.map { "${it.key.name}: ${it.value}" }}")
            }
            finish(); return
        }
        // the database comparison in the app's own (foreground) process
        if (intent?.action == diag.ACTION_GEMMA && intent.getStringExtra("mode") == "db-main") {
            val app = applicationContext
            kotlin.concurrent.thread(name = "db-check") {
                try {
                    if (intent.getStringExtra("slices") != null) com.example.thermohammer.engine.ai.AiGpuDiagnosis.databaseSlices(app)
                    else if (intent.getStringExtra("order") != null) com.example.thermohammer.engine.ai.AiGpuDiagnosis.databaseOrder(app)
                    else com.example.thermohammer.engine.ai.AiGpuDiagnosis.database(app)
                } catch (e: Throwable) { android.util.Log.e("AiGpuDiag", "crashed: $e") }
            }
            finish(); return
        }
        // the run history (numbers only) copied where adb can read it
        if (intent?.action == diag.ACTION_GEMMA && intent.getStringExtra("mode") == "history") {
            runCatching {
                java.io.File(filesDir, "benchmark_history.json").copyTo(java.io.File(getExternalFilesDir(null), "benchmark_history.json"), overwrite = true)
            }.onFailure { android.util.Log.e("History", "not copied: $it") }
            finish(); return
        }
        if (intent?.action == diag.ACTION_GEMMA && intent.getStringExtra("mode") == "tune-main") {
            val app = applicationContext
            kotlin.concurrent.thread(name = "llm-tune-check") {
                try { com.example.thermohammer.engine.ai.LlmTuneQuick.run(app) } catch (e: Throwable) { android.util.Log.e("LlmTune", "crashed: $e") }
            }
            finish(); return
        }
        if (intent?.action == diag.ACTION_RUN || intent?.action == diag.ACTION_GEMMA) {
            val service = Intent(this, com.example.thermohammer.engine.ai.AiGpuDiagnosisService::class.java)
                .setAction(intent.action)
            intent.getStringExtra("only")?.let { service.putExtra("only", it) }
            intent.getStringExtra("mode")?.let { service.putExtra("mode", it) }
            intent.getStringExtra("file")?.let { service.putExtra("file", it) }
            intent.getStringExtra("backend")?.let { service.putExtra("backend", it) }
            val started = runCatching { startService(service) }
            Toast.makeText(this, if (started.isSuccess) "GPU AI diagnosis started (logcat: AiGpuDiag)"
                else "GPU AI diagnosis not started: ${started.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri == null) { finish(); return }
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
        if (!name.endsWith(".litertlm")) {
            Toast.makeText(this, "Not a LiteRT-LM model (.litertlm): $name", Toast.LENGTH_LONG).show()
            finish(); return
        }
        Toast.makeText(this, "Copying $name for the NPU test…", Toast.LENGTH_LONG).show()
        val dir = com.example.thermohammer.engine.llm.NpuModels.testDir(this)
        val app = applicationContext
        thread(name = "npu-model-import") {
            val message = try {
                val part = File(dir, "$name.part")
                contentResolver.openInputStream(uri)!!.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
                val target = File(dir, name)
                target.delete()
                if (!part.renameTo(target)) error("couldn't rename the copy")
                "Ready for the NPU test: $name (${target.length() / 1_000_000} MB)"
            } catch (e: Exception) {
                "Copy failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
        finish()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
