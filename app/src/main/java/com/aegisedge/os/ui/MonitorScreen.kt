package com.aegisedge.os.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisedge.os.core.inference.ModelRegistry
import com.aegisedge.os.core.pipeline.SensePipeline
import com.aegisedge.os.modes.OperationalMode
import com.aegisedge.os.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(
    mode: OperationalMode,
    status: SensePipeline.PipelineStatus,
    onStop: () -> Unit,
    onTriggerScenario: (String) -> Unit, // Kept in signature to satisfy MainActivity
    onOpenLedger: () -> Unit = {},
) {
    val context = LocalContext.current


    val threatColor by animateColorAsState(
        targetValue = when {
            status.lastAssessment.contains("VERIFIED_THREAT") -> ThreatVerified
            status.lastAssessment.contains("ELEVATED") -> ThreatElevated
            status.lastAssessment.contains("AMBIGUOUS") -> ThreatAmbiguous
            else -> ThreatBenign
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "threat"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Fullscreen Camera Background
        if (status.latestFrame != null) {
            Image(
                bitmap = status.latestFrame.asImageBitmap(),
                contentDescription = "Live feed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Waiting for camera stream...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
            }
        }

        // Gradient Overlays for readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
        )

        // Top Navigation & Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            IconButton(onClick = onStop) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            

        }

        // Bottom Content (Logs and Actions)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Massive Threat Assessment Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = threatColor.copy(alpha = 0.85f),
                contentColor = Color.White
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = if (status.lastAssessment.contains("VERIFIED_THREAT")) "THREAT DETECTED" 
                               else if (status.lastAssessment.contains("ELEVATED")) "ELEVATED RISK"
                               else if (status.lastAssessment.contains("AMBIGUOUS")) "OBSERVING"
                               else "ALL CLEAR",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status.lastAssessment.take(200),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            // Logs Terminal
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "NEURO-SYMBOLIC PIPELINE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    
                    val listState = rememberLazyListState()
                    LaunchedEffect(status.logs.size) {
                        if (status.logs.isNotEmpty()) {
                            listState.animateScrollToItem(status.logs.size - 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(status.logs) { log ->
                            Text(
                                text = log,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                color = when {
                                    log.contains("VERIFIED_THREAT") || log.contains("ALERT") -> Color(0xFFFF6E6E)
                                    log.contains("BENIGN") || log.contains("wiped") -> Color(0xFF66BB6A)
                                    else -> Color.White.copy(alpha = 0.8f)
                                }
                            )
                        }
                    }
                }
            }
            // Telemetry Panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                        Text("Active Sensors", fontSize = 10.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Text(if (mode == OperationalMode.SMART_DASHCAM) "CAM+MIC+IMU" else "CAM+MIC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                        Text("Inference FPS", fontSize = 10.sp, color = Color.Gray)
                        Spacer(Modifier.height(2.dp))
                        Text("30.0", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF66BB6A))
                    }
                }
            }
            // Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { /* Upload Logic */ },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Upload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Dispatch")
                }
                
                OutlinedButton(
                    onClick = onOpenLedger,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Verified, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ledger")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
