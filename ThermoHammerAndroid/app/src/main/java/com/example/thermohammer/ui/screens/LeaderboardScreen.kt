package com.example.thermohammer.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerDto
import com.example.thermohammer.ui.components.*
import com.example.thermohammer.ui.overlays.*
import kotlinx.coroutines.launch

data class RankedEntry(val entry: HammerDto, val rank: Int, val stability: Double)

@Composable
fun LeaderboardScreen(isNetworkConnected: Boolean) {
    var entries by remember { mutableStateOf<List<HammerDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<HammerDto?>(null) }
    var detailedStamps by remember { mutableStateOf<List<DeviceHammerStamp>>(emptyList()) }
    var stampsLoading by remember { mutableStateOf(false) }
    var stampsError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun loadData() {
        if (!isNetworkConnected) return
        coroutineScope.launch {
            isLoading = true; errorMsg = null
            try {
                entries = ApiClient.api.fetchLeaderboard()
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to load leaderboard"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    val ranked: List<RankedEntry> = remember(entries) {
        entries.sortedByDescending { it.stabilityPercentage }.mapIndexed { i, e -> RankedEntry(e, i + 1, e.stabilityPercentage) }
    }
    val filtered = remember(ranked, searchText) {
        if (searchText.isEmpty()) ranked
        else ranked.filter { r ->
            r.entry.deviceModel.contains(searchText, ignoreCase = true) ||
            r.entry.deviceManufacturer.contains(searchText, ignoreCase = true) ||
            r.entry.osVersion.contains(searchText, ignoreCase = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("GLOBAL LEADERBOARD", style = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp))
                    Text("Sustained CPU Performance Stability Rankings", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                IconButton(onClick = { loadData() }, enabled = !isLoading) {
                    Text("↻", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (!isNetworkConnected) {
                OfflineView()
            } else if (isLoading) {
                LoadingView()
            } else if (errorMsg != null) {
                ErrorView(errorMsg!!) { loadData() }
            } else {
                // Search field
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌕ ", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (searchText.isEmpty()) Text("Search model, vendor, or OS...", style = TextStyle(color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                            innerTextField()
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("NO ENTRIES FOUND", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filtered) { item ->
                            LeaderboardRow(item, onClick = {
                                selectedEntry = item.entry
                                detailedStamps = emptyList(); stampsError = null; stampsLoading = true
                                coroutineScope.launch {
                                    try {
                                        detailedStamps = ApiClient.api.fetchStamps(item.entry.id)
                                    } catch (e: Exception) {
                                        stampsError = e.message ?: "Failed to load stamps"
                                    }
                                    stampsLoading = false
                                }
                            })
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }

        // Detail overlay
        if (selectedEntry != null) {
            DetailOverlay(
                entry = selectedEntry!!,
                stamps = detailedStamps,
                isLoading = stampsLoading,
                error = stampsError,
                onDismiss = { selectedEntry = null },
                onRetry = {
                    stampsError = null; stampsLoading = true
                    val id = selectedEntry!!.id
                    coroutineScope.launch {
                        try { detailedStamps = ApiClient.api.fetchStamps(id) }
                        catch (e: Exception) { stampsError = e.message }
                        stampsLoading = false
                    }
                }
            )
        }
    }
}

@Composable
private fun LeaderboardRow(item: RankedEntry, onClick: () -> Unit) {
    val entry = item.entry
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Rank badge
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
            when (item.rank) {
                1 -> {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF9CC29), Color(0xFFE6A800)))))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👑", fontSize = 9.sp)
                        Text("1", style = TextStyle(color = Color.Black, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                    }
                }
                2 -> {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFD6D6D6), Color(0xFFA5A5A5)))))
                    Text("2", style = TextStyle(color = Color.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                }
                3 -> {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFCC7A47), Color(0xFF995733)))))
                    Text("3", style = TextStyle(color = Color.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                }
                else -> {
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
                        Text("#${item.rank}", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Device info
        Column(Modifier.weight(1f)) {
            Text(entry.deviceModel, style = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.deviceManufacturer, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                Text("${if (entry.os == 1) "iOS" else "Android"} ${entry.osVersion}", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                val durColor = when (entry.type) { 0 -> Color(0xFF33CC66); 1 -> Color(0xFF4A9EFF); else -> Color(0xFFAB5BFF) }
                Text(when (entry.type) { 0 -> "5 MIN"; 1 -> "15 MIN"; else -> "30 MIN" }, style = TextStyle(color = durColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            }
        }

        // Stability score
        Column(horizontalAlignment = Alignment.End) {
            Text("%.1f%%".format(item.stability), style = TextStyle(color = stabilityColor(item.stability.toFloat()), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
            Text("STABILITY", style = TextStyle(color = Color.White.copy(alpha = 0.35f), fontSize = 7.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun DetailOverlay(
    entry: HammerDto,
    stamps: List<DeviceHammerStamp>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A1A1A))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.deviceModel, style = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                    Text("${entry.deviceManufacturer} • ${if (entry.os == 1) "iOS" else "Android"} ${entry.osVersion}", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black) }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color.White.copy(alpha = 0.1f))

            when {
                isLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color(0xFF4A9EFF), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("LOADING STAMP DATA...", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                    }
                }
                error != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠", fontSize = 32.sp, color = Color(0xFFF2994A))
                        Spacer(Modifier.height(8.dp))
                        Text("FAILED TO LOAD STAMPS", style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Text(error, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp, textAlign = TextAlign.Center))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                            Text("RETRY", style = TextStyle(color = Color.Black, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                else -> {
                    val scores = stamps.map { it.score.toDouble() }
                    val maxScore = scores.maxOrNull() ?: 1.0
                    val minScore = scores.minOrNull() ?: 0.0
                    val stability = if (maxScore > 0) (minScore / maxScore) * 100.0 else entry.stabilityPercentage
                    val chartPts = stamps.map { s -> com.example.thermohammer.engine.StabilityPoint(s.elapsedMs.toFloat() / 1000f, ((s.score.toDouble() / maxScore) * 100.0).toFloat()) }

                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        // Stats
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.03f)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SummaryRow("TEST TYPE", when (entry.type) { 0 -> "5 MIN"; 1 -> "15 MIN"; else -> "30 MIN" })
                            SummaryRow("STABILITY", "%.1f%%".format(stability), stabilityColor(stability.toFloat()))
                            SummaryRow("PEAK SCORE (IPS)", "%,d".format(maxScore.toLong()))
                            SummaryRow("MIN SCORE (IPS)", "%,d".format(minScore.toLong()))
                            SummaryRow("SAMPLES RECORDED", stamps.size.toString())
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("PERFORMANCE CURVE", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                        Spacer(Modifier.height(8.dp))
                        StabilityChart(points = chartPts, events = emptyList())
                    }
                }
            }
        }
    }
}

@Composable private fun OfflineView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⌀", fontSize = 50.sp, color = Color.White.copy(alpha = 0.3f))
            Text("LEADERBOARD OFFLINE", style = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            Text("Please connect to the internet to fetch and view global stability rankings.", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 40.dp))
        }
    }
}

@Composable private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(40.dp), color = Color(0xFF4A9EFF))
            Spacer(Modifier.height(16.dp))
            Text("FETCHING RANKINGS...", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable private fun ErrorView(msg: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⚠", fontSize = 40.sp, color = Color(0xFFEB5757).copy(alpha = 0.8f))
            Text("COULD NOT LOAD LEADERBOARD", style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            Text(msg, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 32.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                Text("RETRY", style = TextStyle(color = Color.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            }
        }
    }
}
