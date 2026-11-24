package com.example.thermohammer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressEngineFactory
import com.example.thermohammer.ui.screens.MainScreen
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.systemBarsPadding

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.view.WindowManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val engineFactory = StressEngineFactory(this)
        val engine = ViewModelProvider(this, engineFactory)[StressEngine::class.java]
        lifecycle.addObserver(engine)
        com.example.thermohammer.telemetry.AppTelemetry.init(this, engine.getDeviceModel(), engine.getSocModel())
        // debug builds: `adb shell am start … --es benchFrom video|export` runs the benchmark from that UX test
        if (savedInstanceState == null && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("benchFrom")?.let { engine.startBenchmark(it) }
        // test builds: `adb shell am start -n <package>/com.example.thermohammer.MainActivity --es benchOnly llm,ai` runs
        // just those benchmark sections (the checklist's picker, without the checklist)
        if (savedInstanceState == null && com.example.thermohammer.TestBuild.PROBES)
            intent.getStringExtra("benchOnly")?.let { list ->
                val only = list.split(',').mapNotNull { n -> com.example.thermohammer.engine.BenchSection.entries.firstOrNull { it.name.equals(n.trim(), true) } }.toSet()
                if (only.isNotEmpty()) engine.startBenchmark(only = only)
            }

        // Test builds: the report-only NPU LLM run's model (not scored in version 5: no NPU path covers every chip yet).
        // The site's list of chips (NpuServerModels) is refreshed too, twice a day at most.
        if (savedInstanceState == null && com.example.thermohammer.TestBuild.PROBES)
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val app = applicationContext
                com.example.thermohammer.engine.llm.NpuServerModels.refreshManifest(app)
                if (com.example.thermohammer.engine.llm.NpuModels.supported(app))
                    com.example.thermohammer.engine.llm.NpuModels.status(app, fetch = true)
            }

        // Prevent screen sleep/lock during CPU stress diagnostic runs
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { state ->
                    if (state.isRunning) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            ThermoHammerTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun ThermoHammerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = com.example.thermohammer.ui.theme.Forge.bg,
            surface = com.example.thermohammer.ui.theme.Forge.surface,
            primary = com.example.thermohammer.ui.theme.Forge.phaseMeasured
        ),
        content = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .background(com.example.thermohammer.ui.theme.Forge.bg)
                    .systemBarsPadding()
            ) {
                content()
            }
        }
    )
}
