package com.example.thermohammer.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.data.PendingResultStore
import com.example.thermohammer.data.PendingTestResult
import com.example.thermohammer.network.ApiClient
import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerDto
import com.example.thermohammer.network.HammerPayload
import com.example.thermohammer.network.ThermoHasher
import com.example.thermohammer.ui.components.*
import com.example.thermohammer.ui.overlays.*
import kotlinx.coroutines.launch

data class RankedEntry(val entry: HammerDto, val rank: Int, val stability: Double)

@Composable
fun LeaderboardScreen(isNetworkConnected: Boolean) {
    val context = LocalContext.current
    val store = remember { PendingResultStore(context) }
    var pendingList by remember { mutableStateOf(store.getAllResults()) }

    var entries by remember { mutableStateOf<List<HammerDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<HammerDto?>(null) }
    var detailedStamps by remember { mutableStateOf<List<DeviceHammerStamp>>(emptyList()) }
    var stampsLoading by remember { mutableStateOf(false) }
    var stampsError by remember { mutableStateOf<String?>(null) }

    // Client-side Filters
    var showOnlyMyDevice by remember { mutableStateOf(false) }
    var showOnlyMyOSVersion by remember { mutableStateOf(false) }
    var selectedPlatformFilter by remember { mutableStateOf<Int?>(null) } // null = All, 1 = iOS, 2 = Android
    var selectedDurationFilter by remember { mutableStateOf<Int?>(null) } // null = All, 0 = 5m, 1 = 15m, 2 = 30m

    // Current device attributes for filtering
    val myDeviceModel = android.os.Build.MODEL
    val myOSVersion = android.os.Build.VERSION.RELEASE

    // Upload status for pending runs
    var uploadingId by remember { mutableStateOf<String?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    // Multi-select comparison state
    var compareSelectedRuns by remember { mutableStateOf<List<PendingTestResult>>(emptyList()) }
    var showAutoCompareOverlay by remember { mutableStateOf(false) }

    fun toggleCompareRun(run: PendingTestResult) {
        if (compareSelectedRuns.any { (run.sessionId > 0 && it.sessionId == run.sessionId) || it.id == run.id }) {
            compareSelectedRuns = compareSelectedRuns.filter { !((run.sessionId > 0 && it.sessionId == run.sessionId) || it.id == run.id) }
        } else {
            if (compareSelectedRuns.isNotEmpty() && compareSelectedRuns.first().testThreadingType != run.testThreadingType) {
                val modeA = if (compareSelectedRuns.first().testThreadingType == 0) "Single Thread" else "Multi Thread"
                val modeB = if (run.testThreadingType == 0) "Single Thread" else "Multi Thread"
                Toast.makeText(context, "Cannot compare $modeA with $modeB test!", Toast.LENGTH_SHORT).show()
                return
            }
            val updated = compareSelectedRuns + run
            if (updated.size >= 2) {
                compareSelectedRuns = updated.take(2)
                showAutoCompareOverlay = true
            } else {
                compareSelectedRuns = updated
            }
        }
    }

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

    LaunchedEffect(isNetworkConnected) {
        loadData()
        pendingList = store.getAllResults()
    }

    val ranked: List<RankedEntry> = remember(entries) {
        entries.sortedByDescending { it.stabilityPercentage }.mapIndexed { i, e -> RankedEntry(e, i + 1, e.stabilityPercentage) }
    }
    val filtered = remember(
        ranked, searchText, showOnlyMyDevice, showOnlyMyOSVersion, 
        selectedPlatformFilter, selectedDurationFilter
    ) {
        ranked.filter { r ->
            val matchesSearch = if (searchText.isEmpty()) true
            else r.entry.deviceModel.contains(searchText, ignoreCase = true) ||
                 r.entry.deviceManufacturer.contains(searchText, ignoreCase = true) ||
                 r.entry.osVersion.contains(searchText, ignoreCase = true)
            
            val matchesDevice = if (!showOnlyMyDevice) true
            else r.entry.deviceModel.equals(myDeviceModel, ignoreCase = true)
            
            val matchesOS = if (!showOnlyMyOSVersion) true
            else r.entry.osVersion.contains(myOSVersion, ignoreCase = true)
            
            val matchesPlatform = when (selectedPlatformFilter) {
                1 -> r.entry.os == 1
                2 -> r.entry.os == 2
                else -> true
            }
            
            val matchesDuration = when (selectedDurationFilter) {
                0 -> r.entry.type == 0
                1 -> r.entry.type == 1
                2 -> r.entry.type == 2
                else -> true
            }
            
            matchesSearch && matchesDevice && matchesOS && matchesPlatform && matchesDuration
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
                    Text("Select 2 Tests to Compare side-by-side", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                }
                IconButton(onClick = { loadData() }, enabled = !isLoading) {
                    Text("↻", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Pending Results Section
                if (pendingList.isNotEmpty()) {
                    item {
                        Text(
                            "PENDING OFFLINE RUNS (${pendingList.size})",
                            style = TextStyle(color = Color(0xFFF2C94C), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(pendingList) { pending ->
                        val isSelected = compareSelectedRuns.any { it.id == pending.id }
                        PendingResultRow(
                            pending = pending,
                            isNetworkConnected = isNetworkConnected,
                            isUploading = uploadingId == pending.id,
                            uploadError = if (uploadingId == pending.id) uploadError else null,
                            isCompareSelected = isSelected,
                            onToggleCompare = { toggleCompareRun(pending) },
                            onSubmit = {
                                uploadingId = pending.id
                                uploadError = null
                                coroutineScope.launch {
                                    try {
                                        val session = ApiClient.api.createSession()
                                        val hash = ThermoHasher.computeHash(session.encryptionKey, pending.stamps)
                                        val payload = HammerPayload(
                                            stamps = pending.stamps,
                                            type = pending.testDurationType,
                                            testThreadingType = pending.testThreadingType,
                                            deviceManufacturer = pending.deviceManufacturer,
                                            deviceModel = pending.deviceModel,
                                            os = 2,
                                            osVersion = pending.osVersion,
                                            sessionId = session.id,
                                            hash = hash
                                        )
                                        ApiClient.api.submitScore(payload)
                                        store.deleteResult(pending.id)
                                        pendingList = store.getAllResults()
                                        uploadingId = null
                                        loadData()
                                    } catch (e: Exception) {
                                        uploadError = e.message ?: "Submission failed"
                                        uploadingId = null
                                    }
                                }
                            },
                            onDelete = {
                                store.deleteResult(pending.id)
                                pendingList = store.getAllResults()
                            }
                        )
                    }
                    item {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))
                    }
                }

                // Search & Filters
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🔍 ", fontSize = 12.sp)
                                    Box(Modifier.weight(1f)) {
                                        if (searchText.isEmpty()) Text("Search by device model, manufacturer, or OS...", style = TextStyle(color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                                        innerTextField()
                                    }
                                }
                            }
                        )

                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CustomFilterChip(selected = showOnlyMyDevice, label = "📱 My Model") { showOnlyMyDevice = !showOnlyMyDevice }
                            CustomFilterChip(selected = showOnlyMyOSVersion, label = "⚙ My OS") { showOnlyMyOSVersion = !showOnlyMyOSVersion }
                            CustomFilterChip(selected = selectedPlatformFilter == 1, label = "🍎 iOS") { selectedPlatformFilter = if (selectedPlatformFilter == 1) null else 1 }
                            CustomFilterChip(selected = selectedPlatformFilter == 2, label = "🤖 Android") { selectedPlatformFilter = if (selectedPlatformFilter == 2) null else 2 }
                            CustomFilterChip(selected = selectedDurationFilter == 0, label = "⏱ 5 Min") { selectedDurationFilter = if (selectedDurationFilter == 0) null else 0 }
                            CustomFilterChip(selected = selectedDurationFilter == 1, label = "⏱ 15 Min") { selectedDurationFilter = if (selectedDurationFilter == 1) null else 1 }
                            CustomFilterChip(selected = selectedDurationFilter == 2, label = "⏱ 30 Min") { selectedDurationFilter = if (selectedDurationFilter == 2) null else 2 }
                        }
                    }
                }

                // Global Leaderboard Entries List
                if (isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF4A9EFF), strokeWidth = 3.dp)
                        }
                    }
                } else if (errorMsg != null) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠ $errorMsg", style = TextStyle(color = Color(0xFFEB5757), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { loadData() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))) {
                                Text("RETRY", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                            Text("No leaderboard entries match filters.", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                        }
                    }
                } else {
                    items(filtered, key = { it.entry.id }) { item ->
                        val itemRun = remember(item.entry) { item.entry.toPendingTestResult() }
                        val isSelected = compareSelectedRuns.any { (itemRun.sessionId > 0 && it.sessionId == itemRun.sessionId) || it.id == itemRun.id }
                        LeaderboardItemRow(
                            item = item,
                            isCompareSelected = isSelected,
                            onToggleCompare = { toggleCompareRun(itemRun) },
                            onClick = {
                                selectedEntry = item.entry
                                detailedStamps = emptyList()
                                stampsLoading = true
                                stampsError = null
                                coroutineScope.launch {
                                    try {
                                        detailedStamps = ApiClient.api.fetchStamps(item.entry.id)
                                    } catch (e: Exception) {
                                        stampsError = e.message ?: "Failed to load stamps"
                                    }
                                    stampsLoading = false
                                }
                            }
                        )
                    }
                }
            }
        }

        // Floating Sticky Selection Bar for Comparison at Bottom of Screen
        if (compareSelectedRuns.isNotEmpty()) {
            val reqMode = if (compareSelectedRuns.first().testThreadingType == 0) "1 THREAD" else "MULTI THREAD"
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF00E5FF).copy(alpha = 0.95f))
                    .clickable {
                        if (compareSelectedRuns.size >= 2) {
                            showAutoCompareOverlay = true
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚖️", fontSize = 16.sp)
                        Column {
                            Text(
                                "${compareSelectedRuns.size} / 2 TESTS SELECTED ($reqMode)",
                                style = TextStyle(color = Color.Black, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                            )
                            Text(
                                if (compareSelectedRuns.size == 1) "Tap 1 more $reqMode test to compare!" else "Tap to view detailed side-by-side analysis",
                                style = TextStyle(color = Color.Black.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.2f))
                            .clickable { compareSelectedRuns = emptyList() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("CLEAR", style = TextStyle(color = Color.Black, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                    }
                }
            }
        }

        // Detail Overlay
        selectedEntry?.let { entry ->
            DetailOverlay(
                entry = entry,
                stamps = detailedStamps,
                isLoading = stampsLoading,
                error = stampsError,
                onDismiss = { selectedEntry = null },
                onRetry = {
                    stampsLoading = true; stampsError = null
                    coroutineScope.launch {
                        try {
                            detailedStamps = ApiClient.api.fetchStamps(entry.id)
                        } catch (e: Exception) {
                            stampsError = e.message ?: "Failed to load stamps"
                        }
                        stampsLoading = false
                    }
                }
            )
        }

        // Auto Comparison Overlay
        if (showAutoCompareOverlay && compareSelectedRuns.size >= 2) {
            ComparisonOverlay(
                preselectedRunA = compareSelectedRuns[0],
                preselectedRunB = compareSelectedRuns[1],
                onDismiss = {
                    showAutoCompareOverlay = false
                    compareSelectedRuns = emptyList()
                }
            )
        }
    }
}

@Composable
private fun PendingResultRow(
    pending: PendingTestResult,
    isNetworkConnected: Boolean,
    isUploading: Boolean,
    uploadError: String?,
    isCompareSelected: Boolean,
    onToggleCompare: () -> Unit,
    onSubmit: () -> Unit,
    onDelete: () -> Unit
) {
    val durationText = when (pending.testDurationType) {
        0 -> "5 MIN"
        1 -> "15 MIN"
        else -> "30 MIN"
    }
    val threadText = if (pending.testThreadingType == 0) "1 THREAD" else "MULTI"
    val threadColor = if (pending.testThreadingType == 0) Color(0xFFFF9500) else Color(0xFF33CC66)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF2C94C).copy(alpha = 0.06f))
            .border(1.dp, Color(0xFFF2C94C).copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("OFFLINE RUN", style = TextStyle(color = Color(0xFFF2C94C), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                    Text("•", color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp)
                    Text(durationText, style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    Text("•", color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp)
                    Text(threadText, style = TextStyle(color = threadColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.height(2.dp))
                Text("${pending.deviceManufacturer} ${pending.deviceModel}", style = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("%.1f%%".format(pending.finalStability), style = TextStyle(color = stabilityColor(pending.finalStability), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                Text("STABILITY", style = TextStyle(color = Color.White.copy(alpha = 0.35f), fontSize = 7.sp, fontFamily = FontFamily.Monospace))
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Compare button toggle (ICON ONLY, NO TEXT)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCompareSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.08f))
                    .border(1.dp, if (isCompareSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleCompare)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    if (isCompareSelected) "✓" else "⚖️",
                    style = TextStyle(color = if (isCompareSelected) Color.Black else Color(0xFF00E5FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                )
            }

            if (isNetworkConnected) {
                if (isUploading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF4A9EFF), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("UPLOADING...", style = TextStyle(color = Color(0xFF4A9EFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace))
                    }
                } else {
                    Button(onClick = onSubmit, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A9EFF))) {
                        Text("SUBMIT", style = TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("DELETE", modifier = Modifier.clickable(onClick = onDelete).padding(4.dp), style = TextStyle(color = Color(0xFFEB5757), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun LeaderboardItemRow(
    item: RankedEntry,
    isCompareSelected: Boolean,
    onToggleCompare: () -> Unit,
    onClick: () -> Unit
) {
    val entry = item.entry
    val threadText = if ((entry.testThreadingType ?: 1) == 0) "1 THREAD" else "MULTI"
    val threadColor = if ((entry.testThreadingType ?: 1) == 0) Color(0xFFFF9500) else Color(0xFF33CC66)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141414))
            .border(1.dp, if (isCompareSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compare Checkbox / Toggle Button (ICON ONLY, NO TEXT)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isCompareSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f))
                .border(1.dp, if (isCompareSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleCompare)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                if (isCompareSelected) "✓" else "⚖️",
                style = TextStyle(color = if (isCompareSelected) Color.Black else Color(0xFF00E5FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            )
        }

        // Rank badge
        Box(contentAlignment = Alignment.Center) {
            when (item.rank) {
                1 -> {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFFFD700), Color(0xFFB8860B)))))
                    Text("1", style = TextStyle(color = Color.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                }
                2 -> {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFC0C0C0), Color(0xFF708090)))))
                    Text("2", style = TextStyle(color = Color.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                }
                3 -> {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFCC7A47), Color(0xFF995733)))))
                    Text("3", style = TextStyle(color = Color.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
                }
                else -> {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
                        Text("#${item.rank}", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Device info & Threading Badge
        Column(Modifier.weight(1f)) {
            val fullModelName = if (entry.deviceModel.startsWith(entry.deviceManufacturer, ignoreCase = true)) {
                entry.deviceModel
            } else {
                "${entry.deviceManufacturer} ${entry.deviceModel}".trim()
            }
            Text(fullModelName, style = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.deviceManufacturer, style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                Box(Modifier.size(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                Text("${if (entry.os == 1) "iOS" else "Android"} ${entry.osVersion}", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                Box(Modifier.size(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
                Text(threadText, style = TextStyle(color = threadColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            }
        }

        // Stability score
        Column(horizontalAlignment = Alignment.End) {
            Text("%.1f%%".format(item.stability), style = TextStyle(color = stabilityColor(item.stability.toFloat()), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
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
                    val fullModelName = if (entry.deviceModel.startsWith(entry.deviceManufacturer, ignoreCase = true)) {
                        entry.deviceModel
                    } else {
                        "${entry.deviceManufacturer} ${entry.deviceModel}".trim()
                    }
                    Text(fullModelName, style = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black))
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
                    val stability = entry.stabilityPercentage
                    val chartPts = stamps.map { s -> com.example.thermohammer.engine.StabilityPoint(s.elapsedMs.toFloat() / 1000f, ((s.score.toDouble() / maxScore) * 100.0).toFloat()) }

                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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

@Composable
fun CustomFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFF2C94C).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) Color(0xFFF2C94C) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (selected) Color(0xFFF2C94C) else Color.White.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
