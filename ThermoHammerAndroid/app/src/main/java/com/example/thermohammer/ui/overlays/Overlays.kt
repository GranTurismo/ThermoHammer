package com.example.thermohammer.ui.overlays

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.ThermalState
import com.example.thermohammer.ui.components.stabilityColor
import com.example.thermohammer.ui.components.thermalColor
import com.example.thermohammer.ui.components.thermalName

// ── Shared UI Helpers ─────────────────────────────────────────────────────────

@Composable
fun OverlayContainer(onDismiss: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.clickable(enabled = false) {} // Eat clicks so inner card doesn't dismiss
        ) { content() }
    }
}

@Composable
fun OverlayCard(width: Int = 320, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(width.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
            .padding(24.dp),
        content = content
    )
}

@Composable
fun MonoLabel(text: String, size: Float = 10f, alpha: Float = 0.5f) = Text(
    text = text,
    style = TextStyle(
        color = Color.White.copy(alpha = alpha),
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold
    )
)

@Composable
fun MonoTitle(text: String, size: Float = 14f) = Text(
    text = text,
    style = TextStyle(
        color = Color.White,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp
    )
)

@Composable
fun OverlayDivider() = HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.Black,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

// ── Summary Row (used in Summary & Leaderboard Detail) ────────────────────────

@Composable
fun SummaryRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MonoLabel(label, size = 11f, alpha = 0.5f)
        Text(
            text = value,
            style = TextStyle(
                color = valueColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black
            )
        )
    }
}

// ── Pre-Test Diagnostics Overlay ──────────────────────────────────────────────

@Composable
fun PreTestOverlay(
    isCharging: Boolean,
    isBatterySaver: Boolean,
    isCellularActive: Boolean,
    onCancel: () -> Unit,
    onProceed: () -> Unit
) {
    OverlayContainer {
        OverlayCard(width = 340) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("☑", fontSize = 32.sp, color = Color(0xFF4A9EFF))
                Spacer(Modifier.height(6.dp))
                MonoTitle("PRE-TEST DIAGNOSTICS", size = 13f)
            }
            Spacer(Modifier.height(16.dp))

            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Phone case
                DiagRow("☐ REMOVE PHONE CASE", "Highly recommended to remove your device case. Trapped heat severely skews throttling scores.", Color(0xFFF2994A))
                // Battery saver
                if (isBatterySaver) DiagRow("⚡ BATTERY SAVER IS ON", "Please turn off Battery Saver / Low Power Mode in Settings to get accurate CPU performance.", Color(0xFFF2994A))
                else DiagRow("✓ BATTERY SAVER OFF", "Battery Saver is disabled. Good to go.", Color(0xFF33CC66), isOk = true)
                // Charging
                if (isCharging) DiagRow("⚡ CHARGER CONNECTED!", "CRITICAL: Unplug your charger. Battery charging emits significant heat that forces thermal throttling.", Color(0xFFEB5757))
                else DiagRow("✓ DISCONNECTED FROM CHARGER", "Device is running on battery.", Color(0xFF33CC66), isOk = true)
                // Cellular
                if (isCellularActive) DiagRow("📡 CELLULAR DETECTED", "Recommend Airplane Mode. Cellular search generates extra background heat.", Color(0xFFF2994A))
                else DiagRow("✓ AIRPLANE / OFFLINE", "Cellular radio is offline.", Color(0xFF33CC66), isOk = true)
                // Foreground
                DiagRow("ℹ KEEP APP IN FOREGROUND", "Minimizing or switching apps cancels the stress test automatically.", Color(0xFF4A9EFF))
            }

            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("CANCEL", onCancel) }
                Box(Modifier.weight(1f)) { PrimaryButton("PROCEED", onProceed) }
            }
        }
    }
}

@Composable
private fun DiagRow(title: String, message: String, color: Color, isOk: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (isOk) "✓" else "!", color = color, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
            Spacer(Modifier.height(3.dp))
            Text(message, style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp), maxLines = 3)
        }
    }
}

// ── Summary Overlay ────────────────────────────────────────────────────────────

@Composable
fun SummaryOverlay(
    duration: Int,
    minStability: Float,
    finalStability: Float,
    worstThermal: ThermalState,
    hasSession: Boolean,
    isNetworkConnected: Boolean,
    isSubmitting: Boolean,
    submitSuccess: String?,
    submitError: String?,
    onSubmit: () -> Unit,
    onSavePending: () -> Unit,
    onDismiss: () -> Unit
) {
    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✓", fontSize = 40.sp, color = Color(0xFF33CC66))
                Spacer(Modifier.height(6.dp))
                MonoTitle("DIAGNOSTIC COMPLETE")
            }
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            val mins = duration / 60; val secs = duration % 60
            SummaryRow("TEST TIME", "%02d:%02d".format(mins, secs))
            SummaryRow("MIN STABILITY", "%.0f%%".format(minStability), stabilityColor(minStability))
            SummaryRow("FINAL STABILITY", "%.0f%%".format(finalStability), stabilityColor(finalStability))
            SummaryRow("WORST THERMAL", thermalName(worstThermal), thermalColor(worstThermal))
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(12.dp))
            
            // Leaderboard & Pending section
            if (hasSession) {
                if (isNetworkConnected) {
                    when {
                        submitSuccess != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Text("✓ ", color = Color(0xFF33CC66), fontWeight = FontWeight.Black)
                            MonoLabel(submitSuccess, size = 11f, alpha = 1f)
                        }
                        submitError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("✗ SUBMISSION FAILED", color = Color(0xFFEB5757), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                            Spacer(Modifier.height(4.dp))
                            Text(submitError, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, textAlign = TextAlign.Center))
                        }
                        isSubmitting -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF4A9EFF), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            MonoLabel("SUBMITTING SCORE...", size = 11f, alpha = 1f)
                        }
                        else -> Button(
                            onClick = onSubmit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A9EFF))
                        ) {
                            Text("👑  SUBMIT SCORE TO LEADERBOARD", style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                        }
                    }
                } else {
                    // Connected to server originally (hasSession is true), but went offline during/after test
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("📡 CONNECTION LOST", color = Color(0xFFF2C94C), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Please enable Wi-Fi or cellular data to submit your score. Alternatively, save this result to pending history to submit it later.",
                            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, textAlign = TextAlign.Center),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = onSavePending,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                                ) {
                                    Text("SAVE PENDING", style = TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = onSubmit,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A9EFF))
                                ) {
                                    Text("RETRY SUBMIT", style = TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            } else {
                // Offline test from start (no session initialized)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🔒 OFFLINE DIAGNOSTIC RUN", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This test was run offline. Enable cellular/Wi-Fi to submit scores in future runs.",
                        style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, textAlign = TextAlign.Center)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS REPORT", onDismiss)
        }
    }
}

// ── Background Cancelled Overlay ──────────────────────────────────────────────

@Composable
fun BackgroundAbortedOverlay(onDismiss: () -> Unit) {
    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✗", fontSize = 40.sp, color = Color(0xFFEB5757))
                Spacer(Modifier.height(6.dp))
                MonoTitle("TEST ABORTED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "The diagnostic test was aborted because the app was minimized or moved to the background. Staying in the foreground is required for accurate thermal stress measurement.",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS", onDismiss)
        }
    }
}

// ── Manual Cancelled Overlay ──────────────────────────────────────────────────

@Composable
fun ManualCancelledOverlay(onDismiss: () -> Unit) {
    OverlayContainer(onDismiss = onDismiss) {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⬛", fontSize = 36.sp, color = Color(0xFFF2994A))
                Spacer(Modifier.height(6.dp))
                MonoTitle("TEST CANCELLED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "The stress test was stopped manually. The diagnostic run was not allowed to finish, and the results are invalid.",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DISMISS", onDismiss)
        }
    }
}

// ── Server Init Overlay ────────────────────────────────────────────────────────

@Composable
fun ServerInitOverlay() {
    OverlayContainer {
        OverlayCard(width = 280) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Color(0xFF4A9EFF), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                MonoTitle("CONNECTING TO SERVER", size = 12f)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Initializing secure diagnostic session for leaderboard verification...",
                    style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
                )
            }
        }
    }
}

// ── Server Error Overlay ───────────────────────────────────────────────────────

@Composable
fun ServerErrorOverlay(errorMessage: String, onCancel: () -> Unit, onRunOffline: () -> Unit) {
    OverlayContainer {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠", fontSize = 40.sp, color = Color(0xFFF2C94C))
                Spacer(Modifier.height(6.dp))
                MonoTitle("SESSION FAILED")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Could not connect to the diagnostic server:\n$errorMessage\n\nWould you like to run in Offline mode instead? (Offline runs cannot be submitted to the leaderboard.)",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("CANCEL", onCancel) }
                Box(Modifier.weight(1f)) { PrimaryButton("RUN OFFLINE", onRunOffline) }
            }
        }
    }
}

// ── Connection Request Overlay ──────────────────────────────────────────────────

@Composable
fun ConnectionRequestOverlay(onTurnedOn: () -> Unit, onSubmitLater: () -> Unit) {
    OverlayContainer {
        OverlayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("📡", fontSize = 40.sp, color = Color(0xFF4A9EFF))
                Spacer(Modifier.height(6.dp))
                MonoTitle("DIAGNOSTICS COMPLETE")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Please enable Wi-Fi or cellular data now to submit your score to the global leaderboard.",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            )
            Spacer(Modifier.height(16.dp))
            OverlayDivider()
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("I TURNED IT ON", onTurnedOn)
                SecondaryButton("SUBMIT LATER", onSubmitLater)
            }
        }
    }
}
