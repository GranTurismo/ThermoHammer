package com.example.thermohammer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Test builds only: adb hooks for the AI section's checks, without a whole benchmark. No UI beyond a toast.
 * This activity is exported and in the foreground, so it may start the diagnosis's unexported service.
 *
 *   adb shell am start -n <package>/com.example.thermohammer.TestToolsActivity -a com.example.thermohammer.RUN_AI_GPU_DIAG [--es only classify,upscale] [--es mode deep|hog|gl|seq|seq-reset|db|classes|compile]
 *   adb shell am start -n <package>/com.example.thermohammer.TestToolsActivity -a com.example.thermohammer.RUN_TEST_TOOL --es mode gpucheck|db-main|history
 */
class TestToolsActivity : Activity() {

    companion object {
        /** The checks that run in the app's own (foreground) process, picked by the extra "mode". */
        const val ACTION_TOOL = "com.example.thermohammer.RUN_TEST_TOOL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val diag = com.example.thermohammer.engine.ai.AiGpuDiagnosisService
        val mode = intent?.getStringExtra("mode")
        // the AI section's GPU compile check (DriverCheck.gpu) alone, from the app's process as the benchmark calls it
        if (intent?.action == ACTION_TOOL && mode == "gpucheck") {
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
        if (intent?.action == ACTION_TOOL && mode == "db-main") {
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
        if (intent?.action == ACTION_TOOL && mode == "history") {
            runCatching {
                java.io.File(filesDir, "benchmark_history.json").copyTo(java.io.File(getExternalFilesDir(null), "benchmark_history.json"), overwrite = true)
            }.onFailure { android.util.Log.e("History", "not copied: $it") }
            finish(); return
        }
        // the GPU AI diagnosis (engine/ai/AiGpuDiagnosis.kt) in its own process; extra "only" = e.g. "classify,upscale"
        if (intent?.action == diag.ACTION_RUN) {
            val service = Intent(this, com.example.thermohammer.engine.ai.AiGpuDiagnosisService::class.java)
                .setAction(intent.action)
            intent.getStringExtra("only")?.let { service.putExtra("only", it) }
            mode?.let { service.putExtra("mode", it) }
            intent.getStringExtra("file")?.let { service.putExtra("file", it) }
            intent.getStringExtra("backend")?.let { service.putExtra("backend", it) }
            val started = runCatching { startService(service) }
            Toast.makeText(this, if (started.isSuccess) "GPU AI diagnosis started (logcat: AiGpuDiag)"
                else "GPU AI diagnosis not started: ${started.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
