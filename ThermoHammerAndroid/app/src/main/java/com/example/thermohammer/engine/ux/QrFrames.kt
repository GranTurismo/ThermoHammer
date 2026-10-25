package com.example.thermohammer.engine.ux

// ─────────────────────────────────────────────────────────────────────────────
// QR SCANNING TEST — the frames a phone camera would hand a scanner.
//
//   FRAMES seeded 1280×720 frames, each a different QR code (payloads of
//   10–180 characters, so versions 2–14) photographed at an angle: rotation,
//   perspective, defocus, uneven light, sensor noise, some low contrast. Every
//   frame is checked to decode before timing, so all phones do identical work.
//   Rendered and scanned by BenchCore (BenchCore/src/qr.cpp, zxing-cpp) — the
//   same C++ on iOS, so an iPhone gets the same frames and the same decoder.
// ─────────────────────────────────────────────────────────────────────────────

class QrFrames(val count: Int = FRAMES, seed: Int = SEED) : AutoCloseable {

    companion object {
        const val FRAMES = 32
        const val SEED = 7
        const val W = 1280
        const val H = 720
    }

    /** One camera frame: NV21 bytes (grey: the luminance, neutral chroma), the text encoded, the code's corners (x, y × 4). */
    class Frame(val nv21: ByteArray, val text: String, val corners: FloatArray)

    private var handle = QrNative.create(count, seed).also { check(it != 0L) { "couldn't prepare the QR frames" } }

    val frames: List<Frame> = (0 until count).map { i ->
        val nv21 = ByteArray(W * H * 3 / 2)
        QrNative.luma(handle, i, nv21)
        nv21.fill(128.toByte(), W * H, nv21.size)
        Frame(nv21, QrNative.text(handle, i), FloatArray(8).also { QrNative.corners(handle, i, it) })
    }

    /**
     * What a scanner app does with one frame — binarize → find → decode (zxing-cpp, the calling thread): whether
     * frame [index] read back as its own text. Where the code was found (4 corners) goes to [found].
     */
    fun scan(index: Int, found: FloatArray? = null): Boolean = QrNative.scan(handle, index, found)

    override fun close() {
        if (handle != 0L) QrNative.destroy(handle)
        handle = 0L
    }
}
