package com.example.thermohammer.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.example.thermohammer.engine.ux.QrFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// UX STAGE — what the screen shows during the UX tests.
//
//   WEB BROWSING, WEB SCROLLING, PHOTO GALLERY: a real WebView at a fixed
//   1920×1080 pixels (scaled down only on smaller screens), so every phone
//   renders the same amount; the pages are assets/web/*.html, read the photos
//   from assets/media through `media/…` and report back through `ThermoBench`.
//   QR SCANNING: the frame being decoded, with where the code was found.
//   (PHOTO EDITING and VIDEO PLAYBACK render through GpuBench, like the GPU tests.)
// ─────────────────────────────────────────────────────────────────────────────

private const val WEB_W = 1920
private const val WEB_H = 1080
private const val BASE_HOST = "thermohammer.local"

/** The JavaScript side's channel to the app (called on WebView's JavaBridge thread). */
private class BenchBridge(
    val onProgress: (String, Double, Int, Double, Int) -> Unit,
    val onDone: (String) -> Unit,
    val onFailed: (String) -> Unit
) {
    @JavascriptInterface fun progress(scene: String, fraction: Double, complexity: Int, fps: Double, index: Int) =
        onProgress(scene, fraction, complexity, fps, index)
    @JavascriptInterface fun done(json: String) = onDone(json)
    @JavascriptInterface fun fail(message: String) = onFailed(message)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBenchView(
    page: String,
    onProgress: (String, Double, Int, Double, Int) -> Unit,
    onDone: (String) -> Unit,
    onFailed: (String) -> Unit
) {
    val context = LocalContext.current
    val html = remember(page) { context.assets.open(page).bufferedReader().use { it.readText() } }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = min(1f, min(constraints.maxWidth.toFloat() / WEB_W, constraints.maxHeight.toFloat() / WEB_H))
        AndroidView(
            modifier = Modifier
                .requiredSize(with(density) { WEB_W.toDp() }, with(density) { WEB_H.toDp() })
                .graphicsLayer { scaleX = scale; scaleY = scale },
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(false)
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setBackgroundColor(0xFF0B0E12.toInt())
                    webViewClient = object : WebViewClient() {
                        // the pages' photos: https://thermohammer.local/media/… → assets/media/…
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val path = request.url.path ?: return null
                            if (request.url.host != BASE_HOST || !path.startsWith("/media/")) return null
                            val name = path.removePrefix("/")
                            val mime = when (name.substringAfterLast('.')) {
                                "webp" -> "image/webp"; "avif" -> "image/avif"; "jpg" -> "image/jpeg"
                                else -> "application/octet-stream"
                            }
                            return try {
                                WebResourceResponse(mime, null, ctx.assets.open(name))
                            } catch (_: Exception) {
                                WebResourceResponse("text/plain", null, 404, "Not Found", emptyMap(), null)
                            }
                        }

                        // a renderer that runs out of memory fails the test instead of taking the app down
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            onFailed(if (detail.didCrash()) "WebView renderer crashed" else "WebView renderer was killed")
                            return true
                        }
                    }
                    addJavascriptInterface(BenchBridge(onProgress, onDone, onFailed), "ThermoBench")
                    loadDataWithBaseURL("https://$BASE_HOST/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() }
        )
    }
}

/** The QR frame under the scanner, half resolution, with the found code outlined. */
@Composable
fun QrBenchView(frames: QrFrames?, index: Int, found: FloatArray?, accent: androidx.compose.ui.graphics.Color) {
    val bitmap by produceState<Bitmap?>(null, frames, index) {
        value = frames?.let { withContext(Dispatchers.Default) { preview(it.frames[index % it.count]) } }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            val pts = found ?: return@Canvas
            // map frame pixels onto the fitted image
            val s = min(size.width / QrFrames.W, size.height / QrFrames.H)
            val ox = (size.width - QrFrames.W * s) / 2; val oy = (size.height - QrFrames.H * s) / 2
            val path = Path()
            for (k in 0 until pts.size / 2) {
                val o = Offset(ox + pts[k * 2] * s, oy + pts[k * 2 + 1] * s)
                drawCircle(accent, radius = 9f, center = o, style = Stroke(4f))
                if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            if (pts.size >= 6) { path.close(); drawPath(path, accent.copy(alpha = 0.8f), style = Stroke(3f)) }
        }
    }
}

private fun preview(f: QrFrames.Frame): Bitmap {
    val w = QrFrames.W / 2; val h = QrFrames.H / 2
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val v = f.nv21[(y * 2) * QrFrames.W + x * 2].toInt() and 0xFF
        px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
