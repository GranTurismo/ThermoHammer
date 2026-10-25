package com.example.thermohammer.engine.mem

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.StatFs
import com.example.thermohammer.engine.DbResult
import com.example.thermohammer.engine.MemTest
import com.example.thermohammer.engine.RamResult
import com.example.thermohammer.engine.StorageResult
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/** What the stage draws for the measurement running now. */
enum class MemAccess { READ, WRITE, COPY, RANDOM_READ, RANDOM_WRITE, CHASE }

/** Live state for the stage: the measurement, how far the test is (0..1) and its rate so far. */
data class MemProgress(val measure: String, val fraction: Float, val rate: Double, val unit: String,
                       val access: MemAccess, val detail: String)

/**
 * The MEM tests. RAM and storage run natively in short slices (cpp/mem_bench.cpp) so the rate on the stage
 * is live; the database test drives Android's own SQLite.
 */
class MemBench(private val context: Context) {

    companion object {
        private const val SLICE_S = 0.25
        private const val MB = 1_000_000.0
        private const val RANDOM_THREADS = 4
        private const val CHATS = 40
        private const val HISTORY = 20_000       // messages already in the database, untimed
    }

    /** Read, write and copy bandwidth with every core streaming, then the latency of one random access. */
    fun ram(isActive: () -> Boolean, onProgress: (MemProgress) -> Unit): RamResult {
        val threads = Runtime.getRuntime().availableProcessors()
        val bytes = if (Process.is64Bit()) 256L shl 20 else 64L shl 20
        val handle = MemNative.ramOpen(bytes, threads)
        check(handle != 0L) { MemNative.lastError() }
        try {
            val detail = "${bytes shr 20} MB BUFFER · $threads THREADS"
            val plan = Plan(MemTest.RAM, listOf(2.0, 2.0, 2.0, 2.5), onProgress)
            val read = plan.run(0, "GB/S", MemAccess.READ, detail, isActive) { MemNative.ramRun(handle, MemNative.READ, it) / 1e9 }
            val write = plan.run(1, "GB/S", MemAccess.WRITE, detail, isActive) { MemNative.ramRun(handle, MemNative.WRITE, it) / 1e9 }
            val copy = plan.run(2, "GB/S", MemAccess.COPY, detail, isActive) { MemNative.ramRun(handle, MemNative.COPY, it) / 1e9 }
            val latency = plan.run(3, "NS", MemAccess.CHASE, "${bytes shr 20} MB · RANDOM ACCESS · 1 THREAD", isActive) {
                MemNative.ramRun(handle, MemNative.LATENCY, it)
            }
            return RamResult(read, write, copy, latency)
        } finally {
            MemNative.ramClose(handle)
        }
    }

    /**
     * A test file of up to 512 MB (a quarter of the free space at most), written sequentially until it has been
     * written whole at least once, then read back, then random 4 KB reads and writes. Deleted afterwards.
     */
    fun storage(isActive: () -> Boolean, onProgress: (MemProgress) -> Unit): StorageResult {
        val dir = context.cacheDir
        val free = StatFs(dir.path).availableBytes
        val bytes = (free / 4).coerceIn(64L shl 20, 512L shl 20) / (4L shl 20) * (4L shl 20)
        val file = File(dir, "membench.bin")
        val handle = MemNative.diskOpen(file.path, bytes)
        check(handle != 0L) { MemNative.lastError() }
        try {
            val mb = (bytes shr 20).toInt()
            fun io() = if (MemNative.diskDirect(handle)) "DIRECT I/O" else "CACHE DROPPED"   // known once reading starts
            fun rate(v: Double): Double { check(v >= 0) { MemNative.lastError() }; return v }
            val plan = Plan(MemTest.STORAGE, listOf(3.0, 2.5, 2.5, 2.5), onProgress)
            // at least one whole pass, so every block read later really is on the flash
            val seqWrite = plan.run(0, "MB/S", MemAccess.WRITE, "$mb MB FILE · 4 MB BLOCKS · SYNCED", isActive,
                more = { elapsed -> MemNative.diskPasses(handle) < 1 || (elapsed < 3.0 && MemNative.diskPasses(handle) < 2) }) {
                rate(MemNative.diskRun(handle, MemNative.SEQ_WRITE, 1, it)) / MB
            }
            val seqRead = plan.run(1, "MB/S", MemAccess.READ, "$mb MB FILE · 4 MB BLOCKS", isActive) {
                rate(MemNative.diskRun(handle, MemNative.SEQ_READ, 1, it)) / MB
            }
            val randomRead = plan.run(2, "IOPS", MemAccess.RANDOM_READ, "4 KB · $RANDOM_THREADS THREADS · ${io()}", isActive) {
                rate(MemNative.diskRun(handle, MemNative.RANDOM_READ, RANDOM_THREADS, it))
            }
            val randomWrite = plan.run(3, "IOPS", MemAccess.RANDOM_WRITE, "4 KB · $RANDOM_THREADS THREADS · ${io()}", isActive) {
                rate(MemNative.diskRun(handle, MemNative.RANDOM_WRITE, RANDOM_THREADS, it))
            }
            return StorageResult(seqRead, seqWrite, randomRead, randomWrite, MemNative.diskDirect(handle), mb)
        } finally {
            MemNative.diskClose(handle)
        }
    }

    /**
     * SQLite like a messaging app, with Android's own settings (WAL): a chat history of 20,000 messages, then new messages
     * saved one transaction each, messages marked read one transaction each, and chats opened (the latest 30
     * messages of a random chat, read through a cursor).
     */
    fun database(isActive: () -> Boolean, onProgress: (MemProgress) -> Unit): DbResult {
        val file = File(context.cacheDir, "membench.db")
        SQLiteDatabase.deleteDatabase(file)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL, sent INTEGER NOT NULL, " +
                "sender TEXT NOT NULL, body TEXT NOT NULL, unread INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX messages_chat_sent ON messages(chat, sent)")
            val rnd = Random(7)
            val bodies = List(64) { body(rnd) }
            val insert = db.compileStatement("INSERT INTO messages(chat, sent, sender, body, unread) VALUES (?, ?, ?, ?, 1)")
            var sent = 1_700_000_000_000L
            fun add() {
                insert.bindLong(1, rnd.nextInt(CHATS).toLong())
                insert.bindLong(2, sent++)
                insert.bindString(3, "contact ${rnd.nextInt(200)}")
                insert.bindString(4, bodies[rnd.nextInt(bodies.size)])
                insert.executeInsert()
            }
            db.beginTransaction()
            try {
                repeat(HISTORY) { add() }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val detail = "SQLITE · WAL · ANDROID'S OWN SETTINGS"
            val plan = Plan(MemTest.DATABASE, listOf(2.5, 2.0, 2.0), onProgress)
            val inserts = plan.run(0, "TX/S", MemAccess.RANDOM_WRITE, detail, isActive) { s -> perSecond(s) { add() } }
            val update = db.compileStatement("UPDATE messages SET unread = 0 WHERE id = ?")
            val rows = HISTORY.toLong()
            val updates = plan.run(1, "TX/S", MemAccess.RANDOM_WRITE, detail, isActive) { s ->
                perSecond(s) { update.bindLong(1, 1 + rnd.nextLong(rows)); update.executeUpdateDelete() }
            }
            val queries = plan.run(2, "CHATS/S", MemAccess.RANDOM_READ, "SQLITE · LATEST 30 MESSAGES OF A CHAT", isActive) { s ->
                perSecond(s) {
                    db.rawQuery("SELECT id, sender, body, sent FROM messages WHERE chat = ? ORDER BY sent DESC LIMIT 30",
                        arrayOf(rnd.nextInt(CHATS).toString())).use { c -> while (c.moveToNext()) c.getString(2) }
                }
            }
            return DbResult(inserts, updates, queries)
        } finally {
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    private inline fun perSecond(seconds: Double, op: () -> Unit): Double {
        val t0 = System.nanoTime()
        val limit = (seconds * 1e9).toLong()
        var n = 0
        while (System.nanoTime() - t0 < limit) { op(); n++ }
        return n * 1e9 / (System.nanoTime() - t0)
    }

    private fun body(rnd: Random): String {
        val words = listOf("see", "you", "at", "the", "station", "tomorrow", "photos", "from", "trip", "are", "great",
            "call", "me", "when", "free", "dinner", "tonight", "running", "late", "sorry", "meeting", "moved", "to", "five")
        return List(8 + rnd.nextInt(24)) { words[rnd.nextInt(words.size)] }.joinToString(" ")
    }

    /** A test's measurements, each given its share of the progress bar by its planned seconds. */
    private class Plan(val test: MemTest, val seconds: List<Double>, val onProgress: (MemProgress) -> Unit) {
        private val total = seconds.sum()

        /**
         * Runs [slice] (one slice of so many seconds → its rate) until the planned time is up, or for as long as
         * [more] says; returns the time-weighted mean rate.
         */
        fun run(index: Int, unit: String, access: MemAccess, detail: String, isActive: () -> Boolean,
                more: ((Double) -> Boolean)? = null, slice: (Double) -> Double): Double {
            val planned = seconds[index]
            val before = seconds.take(index).sum()
            var weighted = 0.0
            var elapsed = 0.0
            onProgress(MemProgress(test.labels[index], (before / total).toFloat(), -1.0, unit, access, detail))
            while (isActive() && (more?.invoke(elapsed) ?: (elapsed < planned - 1e-6))) {
                val s = if (more != null) SLICE_S else min(SLICE_S, planned - elapsed)
                val t0 = System.nanoTime()
                val rate = slice(s)
                val took = (System.nanoTime() - t0) / 1e9
                weighted += rate * took
                elapsed += took
                val done = (before + min(elapsed, planned)) / total
                onProgress(MemProgress(test.labels[index], done.toFloat(), weighted / elapsed, unit, access, detail))
            }
            return if (elapsed > 0) weighted / elapsed else 0.0
        }
    }
}
