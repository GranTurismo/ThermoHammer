package com.example.thermohammer.engine.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * NPU model files that come from ThermoHammer's own site instead of Play (scoring version 5): the Gemma file for a chip
 * that isn't in this release's gemma_npu AI pack — one Google published later, or one we built ourselves
 * (tools/npu_models). The site lists them in models/android/manifest.json (chip → file, SHA-256, size). A phone with one of
 * those chips downloads its file once, over Wi-Fi, checks it and keeps it, so a new chip needs no app update.
 *
 * manifest.json: {"chips": {"sm8635": {"file": "Gemma3-1B-IT_q4_ekv1280_sm8635.litertlm", "sha256": "…", "size": 690000000}}}
 */
object NpuServerModels {
    const val BASE = "https://thermohammer.gtgroup.dev/models/android/"
    private const val MANIFEST_MAX_AGE_MS = 12 * 3600_000L

    data class Entry(val soc: String, val file: String, val sha256: String, val size: Long)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, "npu-llm-server").apply { mkdirs() }
    private fun manifestFile(context: Context) = File(dir(context), "manifest.json")

    /** Every chip the cached manifest lists (no network). */
    fun entries(context: Context): Map<String, Entry> = runCatching {
        val json = JSONObject(manifestFile(context).readText()).getJSONObject("chips")
        json.keys().asSequence().mapNotNull { soc ->
            val e = json.getJSONObject(soc)
            val file = e.getString("file")
            // a file name is a name, nothing else (it becomes a path under our own folder)
            if (file.contains('/') || file.contains("..") || !file.endsWith(".litertlm")) null
            else soc.lowercase() to Entry(soc.lowercase(), file, e.getString("sha256").lowercase(), e.getLong("size"))
        }.toMap()
    }.getOrDefault(emptyMap())

    fun entry(context: Context, soc: String): Entry? = entries(context)[soc.lowercase()]

    /** Fetches the manifest if the cached one is older than 12 h, or with `force`. Blocking (call off the main thread); never throws. */
    fun refreshManifest(context: Context, force: Boolean = false): Boolean {
        val cached = manifestFile(context)
        if (!force && cached.isFile && System.currentTimeMillis() - cached.lastModified() < MANIFEST_MAX_AGE_MS) return true
        return runCatching {
            http.newCall(Request.Builder().url(BASE + "manifest.json").build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching false
                val text = res.body?.string() ?: return@runCatching false
                JSONObject(text).getJSONObject("chips")                     // only a manifest that parses replaces the old one
                val part = File(cached.path + ".part")
                part.writeText(text)
                part.renameTo(cached)
            }
        }.getOrDefault(false)
    }

    /** This chip's downloaded and checked file, or null. */
    fun file(context: Context, soc: String): File? {
        val e = entry(context, soc) ?: return null
        return File(dir(context), e.file).takeIf { it.isFile && it.length() == e.size }
    }

    // ── the download: one at a time, Wi-Fi only, resumed from what's already there ──

    private val running = AtomicBoolean(false)
    /** 0–100 while a download runs, -1 otherwise. */
    @Volatile var progress = -1; private set
    /** Why the last download stopped, or null. */
    @Volatile var lastError: String? = null; private set
    @Volatile var waitingForWifi = false; private set

    fun isRunning() = running.get()

    private fun onUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts this chip's download on a background thread unless one is running. */
    fun startDownload(context: Context, e: Entry) {
        if (!onUnmetered(context)) { waitingForWifi = true; return }
        waitingForWifi = false
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                lastError = null
                download(app, e)
            } catch (t: Throwable) {
                lastError = (t.message ?: t.javaClass.simpleName).take(160)
            } finally {
                progress = -1
                running.set(false)
            }
        }, "npu-model-download").start()
    }

    private fun download(context: Context, e: Entry) {
        val target = File(dir(context), e.file)
        val part = File(target.path + ".part")
        if (part.length() > e.size) part.delete()
        val from = part.length()
        val request = Request.Builder().url(BASE + e.file).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
        http.newCall(request).execute().use { res ->
            val resumed = res.code == 206
            if (!res.isSuccessful) error("the server answered ${res.code}")
            if (!resumed && from > 0) part.delete()                  // the server sent it all again: start over
            val body = res.body ?: error("no data")
            RandomAccessFile(part, "rw").use { out ->
                out.seek(if (resumed) from else 0)
                var written = if (resumed) from else 0L
                val buf = ByteArray(1 shl 20)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        progress = (written * 100 / e.size).toInt().coerceIn(0, 100)
                    }
                }
            }
        }
        if (part.length() != e.size) error("the download stopped at ${part.length() / 1_000_000} of ${e.size / 1_000_000} MB")
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != e.sha256) { part.delete(); error("the download is damaged (SHA-256 ${sha.take(12)}…)") }
        target.delete()
        if (!part.renameTo(target)) error("couldn't keep the file")
        // other chips' files the manifest no longer lists (a phone has one chip, but an old file shouldn't linger)
        dir(context).listFiles().orEmpty().filter { it.name.endsWith(".litertlm") && it.name != e.file }.forEach { it.delete() }
    }
}
