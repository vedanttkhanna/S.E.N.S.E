package com.aegisedge.os.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisedge.os.core.inference.ModelRegistry
import com.aegisedge.os.ui.theme.ThreatBenign
import com.aegisedge.os.ui.theme.ThreatVerified

data class ModelStatus(
    val name: String,
    val filename: String,
    val description: String,
    val isAvailable: Boolean,
    val expectedPath: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val registry = remember { ModelRegistry(context) }

    val models = remember {
        listOf(
            ModelStatus(
                "Gemma 3 1B",
                ModelRegistry.Names.GEMMA3_1B,
                "Layer 3 Neuro-Symbolic Reasoner",
                registry.isAvailable(ModelRegistry.Names.GEMMA3_1B),
                "/data/local/tmp/aegis_models/${ModelRegistry.Names.GEMMA3_1B}"
            ),
            ModelStatus(
                "PaliGemma 3B",
                ModelRegistry.Names.PALIGEMMA_3B,
                "Layer 2 Semantic Vision Engine",
                registry.isAvailable(ModelRegistry.Names.PALIGEMMA_3B),
                "/data/local/tmp/aegis_models/${ModelRegistry.Names.PALIGEMMA_3B}"
            ),
            ModelStatus(
                "Nano YOLO",
                ModelRegistry.Names.NANO_YOLO,
                "Layer 1 Object Detector",
                registry.isAvailable(ModelRegistry.Names.NANO_YOLO),
                "/data/local/tmp/aegis_models/${ModelRegistry.Names.NANO_YOLO}"
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Manager", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Edge-Native AI Weights",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "To ensure absolute privacy, S.E.N.S.E runs strictly on-device. If your models say 'MISSING', the system falls back to a Mock AI cycle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(models) { model ->
                ModelStatusCard(model)
            }
            
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Installation Instructions:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "To install these models on your device, use ADB over USB or WiFi and run:\n\n" +
                    "adb shell mkdir -p /data/local/tmp/aegis_models\n" +
                    "adb push gemma3_1b_int4.task /data/local/tmp/aegis_models/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ModelStatusCard(model: ModelStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (model.isAvailable) ThreatBenign else ThreatVerified)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(model.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (model.isAvailable) ThreatBenign.copy(alpha = 0.1f) else ThreatVerified.copy(alpha = 0.1f)
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (model.isAvailable) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (model.isAvailable) ThreatBenign else ThreatVerified,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (model.isAvailable) "LOADED" else "MISSING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (model.isAvailable) ThreatBenign else ThreatVerified
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            Text("Filename: ${model.filename}", fontSize = 11.sp, color = Color.Gray)
            Text("Path: ${model.expectedPath}", fontSize = 11.sp, color = Color.Gray)
        }
    }
}
