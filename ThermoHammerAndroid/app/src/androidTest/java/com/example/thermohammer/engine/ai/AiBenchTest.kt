package com.example.thermohammer.engine.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.AiAccel
import com.example.thermohammer.engine.AiTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The AI section on a real device: every model runs on every accelerator LiteRT offers here, and its
 * decoded result is saved (ai_<test>.png in the app's external files dir). Rates go to the instrumentation
 * status as "<TEST>_<ACCEL>". `-e accels GPU,NPU` limits the accelerators — the emulator advertises SME2
 * it can't execute, so XNNPACK's CPU kernels crash there.
 */
@RunWith(AndroidJUnit4::class)
class AiBenchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun everyModelRunsAndDecodes() {
        val bench = AiBench(app)
        val inputs = AiInputs(app)
        val only = InstrumentationRegistry.getArguments().getString("accels")?.split(',')?.map { AiAccel.valueOf(it.trim()) }
        val accels = bench.available().filter { only == null || it in only }.toSet()
        val status = Bundle().apply { putString("accelerators", accels.joinToString()); putString("npu_runtime", bench.npuRuntime() ?: "none") }
        for (test in AiTest.entries) {
            val (bytes, picture) = inputs.input(test)
            for (accel in AiAccel.entries) {
                if (accel !in accels) continue
                val m = try {
                    bench.run(test, accel, bytes, 1.0, { true }) { _, _ -> }
                } catch (e: Exception) {
                    status.putString("${test.name}_${accel.name}", "failed: ${e.message}")
                    assertTrue("${test.name} must run on the CPU: ${e.message}", accel != AiAccel.CPU)
                    continue
                }
                status.putString("${test.name}_${accel.name}", "%.1f inf/s".format(m.ips))
                if (!status.containsKey("${test.name}_labels")) {
                    assertTrue("${test.name} CPU rate", m.ips > 0)
                    val v = inputs.visual(test, picture, m.outputs)
                    status.putString("${test.name}_labels", v.labels.joinToString { "%s %.0f%%".format(it.first, it.second * 100) } +
                        v.boxes.take(6).joinToString { "%s %.2f".format(it.label, it.score) })
                    save(v, "ai_${test.name.lowercase()}.png")
                }
            }
        }
        instrumentation.sendStatus(0, status)
    }

    /** The picture with the result laid over or beside it, roughly as the stage draws it. */
    private fun save(v: AiVisual, name: String) {
        val p = v.picture
        val r = v.result
        val out = Bitmap.createBitmap(if (r != null && v.test != AiTest.SEGMENT) p.width * 2 else p.width, p.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(p, null, RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), null)
        if (r != null) {
            val dst = if (v.test == AiTest.SEGMENT) RectF(0f, 0f, p.width.toFloat(), p.height.toFloat())
                else RectF(p.width.toFloat(), 0f, p.width * 2f, p.height.toFloat())
            c.drawBitmap(r, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.YELLOW }
        val text = Paint().apply { color = Color.YELLOW; textSize = 22f }
        for (b in v.boxes) { c.drawRect(b.left, b.top, b.right, b.bottom, box); c.drawText("${b.label} %.2f".format(b.score), b.left, b.top - 4, text) }
        v.labels.forEachIndexed { i, (l, s) -> c.drawText("$l %.0f%%".format(s * 100), 20f, 40f + i * 28, text.apply { textSize = 26f }) }
        FileOutputStream(File(app.getExternalFilesDir(null), name)).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
