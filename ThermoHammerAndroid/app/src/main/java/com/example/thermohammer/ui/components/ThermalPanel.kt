package com.example.thermohammer.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermohammer.engine.StressState

@Composable
fun ThermalMonitoringPanel(state: StressState, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141414).copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "LIVE THERMAL TELEMETRY",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "HIDE ALL SENSORS ▲" else "SHOW ALL SENSORS ▼",
                style = TextStyle(
                    color = Color(0xFFF2C94C),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        
        Spacer(Modifier.height(12.dp))
        
        // Primary Temps Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // CPU Temp Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text("CPU CHIPSET", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.cpuTemp > 0f) "%.1f°C".format(state.cpuTemp) else "-- °C",
                    style = TextStyle(
                        color = when {
                            state.cpuTemp >= 60f -> Color(0xFFEB5757)
                            state.cpuTemp >= 45f -> Color(0xFFF2C94C)
                            else -> Color(0xFF27AE60)
                        },
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black
                    )
                )
            }
            
            // Battery Temp Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text("BATTERY PACK", style = TextStyle(color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.batteryTemp > 0f) "%.1f°C".format(state.batteryTemp) else "-- °C",
                    style = TextStyle(
                        color = when {
                            state.batteryTemp >= 42f -> Color(0xFFEB5757)
                            state.batteryTemp >= 36f -> Color(0xFFF2C94C)
                            else -> Color(0xFF27AE60)
                        },
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black
                    )
                )
            }
        }
        
        // Extended sensors list when expanded
        if (expanded && state.thermalSensors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))
            
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val sortedSensors = state.thermalSensors.sortedByDescending { it.second }
                sortedSensors.forEach { (name, temp) ->
                    val cleanName = name.replace("-", " ").replace("_", " ").uppercase()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cleanName,
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Text(
                            "%.1f°C".format(temp),
                            style = TextStyle(
                                color = when {
                                    temp >= 60f -> Color(0xFFEB5757)
                                    temp >= 45f -> Color(0xFFF2C94C)
                                    else -> Color.White.copy(alpha = 0.8f)
                                },
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}
