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
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegisedge.os.core.memory.ForensicLedger
import com.aegisedge.os.ui.theme.ThreatBenign
import com.aegisedge.os.ui.theme.ThreatVerified
import java.io.File

data class LedgerEntry(
    val timestamp: String,
    val incidentId: String,
    val videoHash: String,
    val audioHash: String,
    val manifestHash: String,
    val prevHash: String,
    val chainHash: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForensicLedgerScreen(
    enclaveDir: File,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<LedgerEntry>>(emptyList()) }
    var chainValid by remember { mutableStateOf<Boolean?>(null) }
    var corruptEntryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ledger = ForensicLedger(enclaveDir)
        val rawEntries = ledger.entries()
        entries = rawEntries.mapNotNull { line ->
            val fields = line.split('|')
            if (fields.size >= 7) {
                LedgerEntry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])
            } else null
        }
        val invalidEntry = ledger.verifyChain()
        corruptEntryId = invalidEntry
        chainValid = invalidEntry == null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forensic Ledger", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Chain Status Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (chainValid == true)
                            ThreatBenign.copy(alpha = 0.08f)
                        else if (chainValid == false)
                            ThreatVerified.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (chainValid == true) Icons.Filled.CheckCircle
                            else if (chainValid == false) Icons.Filled.Error
                            else Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = if (chainValid == true) ThreatBenign
                            else if (chainValid == false) ThreatVerified
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                when (chainValid) {
                                    true -> "Chain Integrity Verified ✓"
                                    false -> "Chain Integrity BROKEN ✗"
                                    null -> "Verifying chain…"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (chainValid == true) ThreatBenign
                                else if (chainValid == false) ThreatVerified
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (chainValid == false && corruptEntryId != null) {
                                Text(
                                    "Corrupt entry detected: $corruptEntryId",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ThreatVerified,
                                )
                            } else {
                                Text(
                                    "${entries.size} forensic incident(s) recorded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Shield,
                                null,
                                tint = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No forensic incidents recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Evidence will appear here after verified threats.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            items(entries.reversed()) { entry ->
                LedgerEntryCard(entry)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LedgerEntryCard(entry: LedgerEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ThreatVerified)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        entry.incidentId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        entry.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            HashRow("Video SHA-256", entry.videoHash)
            Spacer(Modifier.height(4.dp))
            HashRow("Audio SHA-256", entry.audioHash)
            Spacer(Modifier.height(4.dp))
            HashRow("Manifest SHA-256", entry.manifestHash)
            Spacer(Modifier.height(4.dp))
            HashRow("Chain Link", entry.chainHash)
        }
    }
}

@Composable
private fun HashRow(label: String, hash: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            hash.take(48) + if (hash.length > 48) "…" else "",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 14.sp,
        )
    }
}
