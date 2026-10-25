package com.example.thermohammer.engine.ux

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The QR test's frames must be the same on every phone, and every one of them must be readable. The frames'
 * fingerprint goes to the instrumentation status — it must match BenchCore's host test (and so iOS).
 */
@RunWith(AndroidJUnit4::class)
class QrFramesTest {

    companion object {
        private val frames by lazy { QrFrames() }

        @JvmStatic @AfterClass
        fun close() = frames.close()
    }

    @Test
    fun everyFrameDecodesToItsOwnText() {
        val found = FloatArray(8)
        for (i in 0 until frames.count) assertTrue("frame $i", frames.scan(i, found))
        assertEquals(QrFrames.FRAMES, frames.frames.map { it.text }.toSet().size)
    }

    @Test
    fun framesAreDeterministic() {
        QrFrames(count = 4).use { again ->
            for (i in 0 until 4) assertArrayEquals("frame $i", frames.frames[i].nv21, again.frames[i].nv21)
        }
        var h = -0x340d631b7bdddcdbL          // FNV-1a over every frame's pixels, as in BenchCore/test/core_test.cpp
        for (f in frames.frames) for (p in 0 until QrFrames.W * QrFrames.H) h = (h xor (f.nv21[p].toLong() and 0xFF)) * 0x100000001b3L
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("qr fingerprint", "%016x".format(h)) })
    }

    @Test
    fun framesLookLikeCameraShots() {
        // payloads of many lengths, so many QR versions; codes rotated and skewed, not axis-aligned
        val lengths = frames.frames.map { it.text.length }
        assertTrue(lengths.max() - lengths.min() > 100)
        val skewed = frames.frames.count { f -> kotlin.math.abs(f.corners[1] - f.corners[3]) > 8f }
        assertTrue("skewed frames $skewed", skewed > QrFrames.FRAMES / 2)
    }
}
