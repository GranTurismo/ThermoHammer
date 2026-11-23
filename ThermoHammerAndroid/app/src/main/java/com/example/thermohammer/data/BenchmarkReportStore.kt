package com.example.thermohammer.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Each benchmark run's full text report (BenchmarkReport.text), kept beside its history record under the record's id,
 * so MY RUNS can copy it later. The history keeps a run's numbers; finding a problem needs its lines — the tuning, the
 * GPU checks, the probes — and a report not copied at once was gone. A report goes when its run leaves the history.
 *
 * Test builds also leave a copy in the app's folder on shared storage (Android/data/…/files/reports), where adb can
 * read it without anyone copying anything.
 */
class BenchmarkReportStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "reports")

    /** Keeps `text` for the run `id`; reports of runs not in `keepIds` (gone from the history) are deleted. Never throws. */
    fun save(id: String, text: String, keepIds: Set<String>, score: Int, timestamp: Long) {
        try {
            dir.mkdirs()
            File(dir, "$id.txt").writeText(text)
            dir.listFiles().orEmpty().filter { it.nameWithoutExtension !in keepIds && it.nameWithoutExtension != id }.forEach { it.delete() }
            if (com.example.thermohammer.TestBuild.PROBES) mirror(text, score, timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "report not saved: $e")                     // best-effort, like the history; never breaks a finished run
        }
    }

    fun load(id: String): String? = runCatching { File(dir, "$id.txt").takeIf { it.exists() }?.readText() }.getOrNull()

    private fun mirror(text: String, score: Int, timestamp: Long) {
        val shared = context.getExternalFilesDir("reports") ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(timestamp))
        File(shared, "report-$stamp-$score.txt").writeText(text)
        shared.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MIRRORED).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "BenchmarkReports"
        const val MIRRORED = 50
    }
}
