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
}
