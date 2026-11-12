package com.example.thermohammer.engine.ux

/** JNI surface of the QR scanning test in libbenchcore.so (BenchCore/src/qr.cpp, zxing-cpp; shared with iOS). */
internal object QrNative {
    init {
        System.loadLibrary("benchcore")
    }

    /** Renders [count] frames from [seed], each checked to decode; 0 if they can't be made. */
    @JvmStatic external fun create(count: Int, seed: Int): Long
    @JvmStatic external fun count(handle: Long): Int
    /** Frame [index]'s W × H luminance bytes into the start of [out]. */
    @JvmStatic external fun luma(handle: Long, index: Int, out: ByteArray)
    @JvmStatic external fun text(handle: Long, index: Int): String
    /** Where the code was drawn: x, y of its 4 corners. */
    @JvmStatic external fun corners(handle: Long, index: Int, out: FloatArray)
    /** Scans frame [index] on the calling thread: whether it read back its own text; where it was found goes to [found]. */
    @JvmStatic external fun scan(handle: Long, index: Int, found: FloatArray?): Boolean
    @JvmStatic external fun destroy(handle: Long)

    /** [text] as a QR code (ECC M): its modules row by row into [out] (1 dark, 0 light, no quiet zone); the side, 0 on failure. */
    @JvmStatic external fun encode(text: String, out: ByteArray): Int

    /** [text]'s QR modules and their side — null if it can't be encoded. The largest code (version 40) is 177 × 177. */
    fun qr(text: String): QrModules? {
        val out = ByteArray(177 * 177)
        val side = encode(text, out)
        return if (side > 0) QrModules(side, out.copyOf(side * side)) else null
    }
}

/** A QR code: [side] × [side] modules, row by row, 1 = dark. */
class QrModules(val side: Int, val modules: ByteArray) {
    fun dark(x: Int, y: Int): Boolean = modules[y * side + x].toInt() != 0
}
