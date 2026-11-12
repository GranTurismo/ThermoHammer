package com.example.thermohammer.ui.overlays

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.ShareLinks
import com.example.thermohammer.engine.ux.QrModules
import com.example.thermohammer.engine.ux.QrNative
import com.example.thermohammer.ui.theme.*
import kotlin.math.floor

// ─────────────────────────────────────────────────────────────────────────────
// VERIFIED HEALTH CERTIFICATE — for selling a used phone
//
//   The certificate is a page on the website (SITE/cert/{id}/), built from the
//   run as the server verified it. The phone being sold shows it as a QR code,
//   so the buyer scans it from the device itself. A run that isn't on the
//   leaderboard yet gets the submit button first.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * [serverId]: the run's leaderboard id, null until submitted. [date] "12 Nov 2026"; [healthLine] the Phone Health
 * sentence when it is at hand ("98% of the median HONOR 600 Pro (14 runs)").
 */
@Composable
fun HealthCertificateOverlay(
    device: String,
    osVersion: String,
    date: String,
    total: Int,
    healthLine: String?,
    serverId: Int?,
    isNetworkConnected: Boolean,
    isSubmitting: Boolean,
    submitError: String?,
    onSubmit: () -> Unit,
    onShareLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // a buyer may take a while to find their camera
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    OverlayContainer {
        OverlayCard(width = 340) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel("THERMOHAMMER", size = 9f)
                Spacer(Modifier.height(6.dp))
                MonoTitle("VERIFIED HEALTH CERTIFICATE", size = 13f, color = Forge.phaseMeasured)
                Spacer(Modifier.height(14.dp))
                Text(device, style = TextStyle(color = Forge.ink0, fontSize = 22.sp, fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center)
                Text("Android $osVersion · $date", style = ThermoType.data(10f, Forge.ink1), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(ShareLinks.points(total), style = ThermoType.data(34f, Forge.ink0, FontWeight.Black))
                MonoLabel("TOTAL POINTS", size = 9f)
                healthLine?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("PHONE HEALTH · $it", style = ThermoType.data(10f, Forge.phaseMeasured, FontWeight.Bold), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                if (serverId == null) NotOnBoard(isNetworkConnected, isSubmitting, submitError, onSubmit)
                else {
                    val url = ShareLinks.certUrl(serverId)
                    QrCode(url, Modifier.size(248.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(ShareLinks.display(url), style = ThermoType.data(10f, Forge.phaseMeasured, FontWeight.Bold), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Buyer: scan this code from the phone being sold. The page shows this exact run as the server verified it.",
                        style = ThermoType.annotation(9f, Forge.ink1), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("SHARE LINK", { onShareLink(url) }, accent = Forge.phaseMeasured)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("DONE", onDismiss)
            }
        }
    }
}

@Composable
private fun NotOnBoard(online: Boolean, submitting: Boolean, error: String?, onSubmit: () -> Unit) {
    Text("The certificate is a page on thermohammer.gtgroup.dev, so the run has to be on the leaderboard first.",
        style = ThermoType.data(10f, Forge.ink0), textAlign = TextAlign.Center)
    Spacer(Modifier.height(14.dp))
    when {
        submitting -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Forge.phasePreFlight, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            MonoLabel("SUBMITTING…", size = 10f, alpha = 0.7f)
        }
        online -> PrimaryButton("SUBMIT TO LEADERBOARD", onSubmit, accent = Forge.phaseMeasured)
        else -> Text("Turn on Wi-Fi or mobile data to submit this run.", style = ThermoType.annotation(9f, Ramp.at(0.5f)),
            textAlign = TextAlign.Center)
    }
    error?.let {
        Text("⚠ $it", style = ThermoType.annotation(9f, Ramp.at(0.85f)), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * [text] as a QR code: dark modules on white with the 4-module quiet zone, each module a whole number of pixels so the
 * code stays crisp at any size.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { runCatching { QrNative.qr(text) }.getOrNull() }
    if (qr == null) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Forge.surface), contentAlignment = Alignment.Center) {
            Text("QR code unavailable", style = ThermoType.annotation(9f, Ramp.at(0.85f)))
        }
        return
    }
    val image = remember(qr) { qrBitmap(qr).asImageBitmap() }
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color.White)
        .border(1.dp, Forge.hairline, RoundedCornerShape(6.dp))) {
        val n = image.width
        val cell = floor(size.minDimension / n).coerceAtLeast(1f).toInt()
        val dim = cell * n
        drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(n, n),
            dstOffset = IntOffset(((size.width - dim) / 2).toInt(), ((size.height - dim) / 2).toInt()), dstSize = IntSize(dim, dim),
            filterQuality = FilterQuality.None)
    }
}

/** One pixel per module, the quiet zone included. */
fun qrBitmap(qr: QrModules, quiet: Int = 4): Bitmap {
    val n = qr.side + 2 * quiet
    val px = IntArray(n * n) { android.graphics.Color.WHITE }
    for (y in 0 until qr.side) for (x in 0 until qr.side)
        if (qr.dark(x, y)) px[(y + quiet) * n + x + quiet] = android.graphics.Color.BLACK
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}
