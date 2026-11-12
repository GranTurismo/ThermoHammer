package com.example.thermohammer.ui.share

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.ThermoHammerTheme
import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.data.SpeedCheckSession
import com.example.thermohammer.data.SpeedCheckStore
import com.example.thermohammer.engine.SpeedCheck
import com.example.thermohammer.engine.SpeedMeasurement
import com.example.thermohammer.ui.overlays.HealthCertificateOverlay
import com.example.thermohammer.ui.overlays.PresentContent
import com.example.thermohammer.ui.overlays.SpeedCheckOverlay
import com.example.thermohammer.ui.theme.Forge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp

/**
 * The 2.1 screens drawn at a phone's size, saved as PNGs in the app's external files dir (features/): the two share
 * cards (straight from RunCardArt), the presentation screen both ways, the certificate with its QR code and the
 * lost-speed diagnosis and comparison.
 */
@RunWith(AndroidJUnit4::class)
class FeatureRenderTest {

    @get:Rule val rule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    private fun save(bmp: Bitmap, name: String) {
        val dir = File(app.getExternalFilesDir(null), "features").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val bench = RunCard.Benchmark(device = "HONOR 600 Pro", os = "Android 16", date = "12 Nov 2026", chip = "SM8750",
        verified = true, url = ShareLinks.runUrl(1234), mine = true, total = 36512,
        sections = RunCard.sections(cpu = 7120, mem = 5980, gpu = 6240, ux = 6610, ai = 5530, llm = 5032))

    /** A 15-minute run: full speed, then a throttle from 3:20 settling near 78 %. */
    private val curve = List(120) { i ->
        val t = i * 7.5
        if (t < 200) 100.0 - (i % 3) * 0.6 else 78.0 + 18.0 * exp(-(t - 200) / 90.0) + (i % 4) * 0.7
    }
    private val stress = RunCard.Stress(device = "Google Pixel 9 Pro", os = "Android 16", date = "12 Nov 2026", chip = "Tensor G4",
        verified = true, url = ShareLinks.stressUrl(567), mine = true, capacity = 83.4, minutes = 15, load = "CPU",
        threads = "MULTI THREAD", curve = curve, onsetSec = 205)

    @Test fun shareCards() {
        save(RunCardArt.render(app, bench), "share_benchmark.png")
        save(RunCardArt.render(app, stress), "share_stress.png")
    }

    @Test fun presentStressPortrait() = capture("present_stress_portrait.png", 412, 860) { PresentContent(stress) {} }

    // the landscape ones need the emulator turned sideways (adb: user_rotation 1) — a box can't be wider than the screen
    @Test fun presentBenchmarkLandscape() = capture("present_benchmark_landscape.png", 860, 400) { PresentContent(bench) {} }

    @Test fun presentStressLandscape() = capture("present_stress_landscape.png", 860, 400) { PresentContent(stress) {} }

    @Test fun presentBenchmarkPortrait() = capture("present_benchmark_portrait.png", 412, 860) { PresentContent(bench) {} }

    @Test fun certificate() = capture("certificate.png", 412, 900) {
        HealthCertificateOverlay(device = "HONOR 600 Pro", osVersion = "16", date = "12 Nov 2026", total = 36512,
            healthLine = "98% of the median HONOR 600 Pro (14 runs)", serverId = 1234, isNetworkConnected = true,
            isSubmitting = false, submitError = null, onSubmit = {}, onShareLink = {}, onDismiss = {})
    }

    private fun measurement(burst: Double, sustained: Double, saver: Boolean, ts: Long) = SpeedMeasurement(
        perSecond = List(30) { s -> if (s < 6) burst - s * 3 else sustained + (s % 3) * 4 + if (s < 10) (10 - s) * 40 else 0 },
        powerSaver = saver, charging = false, thermalStatus = 0, timestamp = ts)

    @Test fun lostSpeedDiagnosis() {
        val now = System.currentTimeMillis()
        SpeedCheckStore(app).save(SpeedCheckSession(measurement(1480.0, 690.0, saver = true, ts = now)))
        capture("lost_speed_diagnosis.png", 412, 1100) {
            SpeedCheckOverlay(busy = false, health = SpeedCheck.HealthNote(82, "HONOR 600 Pro", slower = true)) {}
        }
    }

    @Test fun lostSpeedComparison() {
        val now = System.currentTimeMillis()
        SpeedCheckStore(app).save(SpeedCheckSession(measurement(1480.0, 690.0, saver = true, ts = now - 120_000),
            measurement(1500.0, 1395.0, saver = false, ts = now)))
        capture("lost_speed_comparison.png", 412, 900) { SpeedCheckOverlay(busy = false, health = null) {} }
    }

    private fun capture(name: String, w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            ThermoHammerTheme { Box(Modifier.size(w.dp, h.dp).background(Forge.bg)) { content() } }
        }
        rule.waitForIdle()
        save(rule.onRoot().captureToImage().asAndroidBitmap(), name)
    }
}
