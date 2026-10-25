package com.example.thermohammer.engine.ux

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** Result of the QR scanning test. ZXing is scored; ML Kit is reported alongside. */
data class QrResult(
    val zxingRate: Double,          // frames decoded per second, one thread
    val zxingDecoded: Int,
    val zxingMissed: Int,
    val mlkitRate: Double?,         // null if ML Kit couldn't run here
    val mlkitError: String? = null
)

/** Live state for the stage: which frame is being read, where the code was found, the running rate. */
data class QrProgress(val phase: String, val fraction: Float, val rate: Double, val frame: Int, val points: FloatArray?)

/**
 * Times scanning of [QrFrames]: ZXing (zxing-cpp, as on iOS) on the calling thread for `zxingSeconds`, then Google ML Kit's
 * bundled scanner for `mlkitSeconds` (after one warm-up call, so model loading isn't timed).
 */
class QrBench(private val frames: QrFrames) {

    fun run(zxingSeconds: Double, mlkitSeconds: Double, isActive: () -> Boolean,
            onProgress: (QrProgress) -> Unit): QrResult {
        // ── ZXing (zxing-cpp) ──
        var decoded = 0; var missed = 0; var i = 0
        val found = FloatArray(8)
        val t0 = System.nanoTime()
        var lastReport = 0L
        val limit = (zxingSeconds * 1e9).toLong()
        while (System.nanoTime() - t0 < limit && isActive()) {
            val ok = frames.scan(i % frames.count, found)
            if (ok) decoded++ else missed++
            val now = System.nanoTime()
            if (now - lastReport > 120_000_000L) {
                lastReport = now
                onProgress(QrProgress("ZXING", ((now - t0).toFloat() / limit).coerceAtMost(1f),
                    decoded * 1e9 / (now - t0), i % frames.count, if (ok) found.copyOf() else null))
            }
            i++
        }
        val zxingRate = decoded * 1e9 / (System.nanoTime() - t0)

        // ── ML Kit (bundled model) ──
        var mlRate: Double? = null
        var mlError: String? = null
        if (mlkitSeconds > 0 && isActive()) {
            var scanner: BarcodeScanner? = null
            try {
                val sc = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                scanner = sc
                fun image(f: QrFrames.Frame) =
                    InputImage.fromByteArray(f.nv21, QrFrames.W, QrFrames.H, 0, InputImage.IMAGE_FORMAT_NV21)
                Tasks.await(sc.process(image(frames.frames[0])))           // loads the model
                var ok = 0; var j = 0
                val m0 = System.nanoTime()
                val mLimit = (mlkitSeconds * 1e9).toLong()
                lastReport = 0L
                while (System.nanoTime() - m0 < mLimit && isActive()) {
                    val f = frames.frames[j % frames.count]
                    val found = Tasks.await(sc.process(image(f)))
                    val hit = found.firstOrNull { it.rawValue == f.text }
                    if (hit != null) ok++
                    val now = System.nanoTime()
                    if (now - lastReport > 120_000_000L) {
                        lastReport = now
                        val pts = hit?.cornerPoints?.let { c -> FloatArray(c.size * 2) { k -> if (k % 2 == 0) c[k / 2].x.toFloat() else c[k / 2].y.toFloat() } }
                        onProgress(QrProgress("ML KIT", ((now - m0).toFloat() / mLimit).coerceAtMost(1f),
                            ok * 1e9 / (now - m0), j % frames.count, pts))
                    }
                    j++
                }
                mlRate = ok * 1e9 / (System.nanoTime() - m0)
            } catch (t: Throwable) {
                mlError = t.message ?: t.javaClass.simpleName
            } finally {
                scanner?.close()
            }
        }
        return QrResult(zxingRate, decoded, missed, mlRate, mlError)
    }
}
