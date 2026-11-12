package com.example.thermohammer.ui.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The system share sheet: an image with its text, a file, or a plain link. Files go through the app's FileProvider
 * (res/xml/file_paths.xml) from cache/share and cache/export, overwritten each time.
 */
object ShareSheet {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Draws [card] (off the main thread), saves it as cache/share/[fileName] and offers it with its text. */
    suspend fun shareCard(context: Context, card: RunCard, fileName: String) {
        val uri = try {
            withContext(Dispatchers.Default) {
                val bmp = RunCardArt.render(context, card)
                val file = File(File(context.cacheDir, "share").apply { mkdirs() }, fileName)
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                FileProvider.getUriForFile(context, authority(context), file)
            }
        } catch (e: Exception) {
            Log.w("ShareSheet", "share card not made: $e")
            Toast.makeText(context, "Couldn't make the image: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        send(context, Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, card.shareText)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    /** Saves [content] as cache/export/[fileName] (UTF-8) and offers it as a [mime] file. */
    suspend fun shareFile(context: Context, content: String, fileName: String, mime: String = "text/csv") {
        val uri: Uri = try {
            withContext(Dispatchers.IO) {
                val file = File(File(context.cacheDir, "export").apply { mkdirs() }, fileName)
                file.writeText(content, Charsets.UTF_8)
                FileProvider.getUriForFile(context, authority(context), file)
            }
        } catch (e: Exception) {
            Log.w("ShareSheet", "export not written: $e")
            Toast.makeText(context, "Couldn't write the file: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        send(context, Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    fun shareText(context: Context, text: String) =
        send(context, Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) })

    private fun send(context: Context, intent: Intent) {
        val chooser = Intent.createChooser(intent, null)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }
}
