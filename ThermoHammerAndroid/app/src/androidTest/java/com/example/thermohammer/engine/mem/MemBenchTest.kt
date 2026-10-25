package com.example.thermohammer.engine.mem

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.MemResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The MEM tests on a device: every measurement positive, the test files gone afterwards. Values go to the instrumentation status. */
@RunWith(AndroidJUnit4::class)
class MemBenchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun ramStorageAndDatabase() {
        val bench = MemBench(app)
        val ram = bench.ram({ true }) {}
        val storage = bench.storage({ true }) {}
        val db = bench.database({ true }) {}
        val r = MemResult(ram, storage, db)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("ram", "read %.1f GB/s · write %.1f · copy %.1f · latency %.1f ns · %d pts".format(
                ram.read, ram.write, ram.copy, ram.latencyNs, r.ramPoints))
            putString("storage", "seq read %.0f MB/s · seq write %.0f · random read %.0f IOPS · random write %.0f · direct %b · %d MB · %d pts".format(
                storage.seqRead, storage.seqWrite, storage.randomRead, storage.randomWrite, storage.direct, storage.fileMb, r.storagePoints))
            putString("database", "insert %.0f tx/s · update %.0f · query %.0f/s · %d pts".format(db.insert, db.update, db.query, r.dbPoints))
            putString("mem", "${r.score}")
        })
        for (v in listOf(ram.read, ram.write, ram.copy, ram.latencyNs, storage.seqRead, storage.seqWrite,
                storage.randomRead, storage.randomWrite, db.insert, db.update, db.query)) assertTrue("positive: $v", v > 0)
        assertFalse(File(app.cacheDir, "membench.bin").exists())
        assertFalse(File(app.cacheDir, "membench.db").exists())
    }
}
