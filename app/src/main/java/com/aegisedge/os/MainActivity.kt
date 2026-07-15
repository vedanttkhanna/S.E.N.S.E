package com.aegisedge.os

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegisedge.os.core.pipeline.SensePipeline
import com.aegisedge.os.core.pipeline.SentinelForegroundService
import com.aegisedge.os.modes.OperationalMode
import com.aegisedge.os.ui.ForensicLedgerScreen
import com.aegisedge.os.ui.ModeSelectorScreen
import com.aegisedge.os.ui.ModelManagerScreen
import com.aegisedge.os.ui.MonitorScreen
import com.aegisedge.os.ui.theme.SenseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class Screen {
    SELECTOR, MONITOR, FORENSIC_LEDGER, MODEL_MANAGER
}

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> by lazy {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private var permissionDenied = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            permissionDenied.value = !allGranted
        }

    private val activeModeState = mutableStateOf<OperationalMode?>(null)
    private var pendingMode: OperationalMode? = null
    private var currentScreenState = mutableStateOf(Screen.SELECTOR)
    private var showOptionsForModeState = mutableStateOf<OperationalMode?>(null)

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val mode = pendingMode ?: return@registerForActivityResult
                activeModeState.value = mode
                currentScreenState.value = Screen.MONITOR
                showOptionsForModeState.value = null
                startSentinel(mode, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val needsPermissions = requiredPermissions.any {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needsPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            SenseTheme {
                val isDenied by permissionDenied
                val activeMode by activeModeState
                var currentScreen by currentScreenState
                var showOptionsForMode by showOptionsForModeState

                if (isDenied) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Permissions Required") },
                        text = { Text("S.E.N.S.E. needs Camera, Microphone, and Location to function. Please grant them in app settings.") },
                        confirmButton = {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = Uri.fromParts("package", packageName, null)
                                startActivity(intent)
                            }) {
                                Text("Open Settings")
                            }
                        }
                    )
                }

                if (showOptionsForMode != null) {
                    AlertDialog(
                        onDismissRequest = { showOptionsForMode = null },
                        title = { Text("Configure S.E.N.S.E.") },
                        text = { Text("Choose input source for ${showOptionsForMode!!.ruleSet.displayName}:") },
                        confirmButton = {
                            TextButton(onClick = {
                                val targetMode = showOptionsForMode!!
                                showOptionsForMode = null
                                activeModeState.value = targetMode
                                currentScreen = Screen.MONITOR
                                startSentinel(targetMode, null)
                            }) {
                                Text("Live Phone Camera")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                val targetMode = showOptionsForMode!!
                                pendingMode = targetMode
                                videoPickerLauncher.launch("video/*")
                            }) {
                                Text("Upload Video File")
                            }
                        }
                    )
                }

                when (currentScreen) {
                    Screen.SELECTOR -> {
                        ModeSelectorScreen(
                            onSelectMode = { mode ->
                                showOptionsForMode = mode
                            },
                            onOpenLedger = { currentScreen = Screen.FORENSIC_LEDGER },
                            onOpenModelManager = { currentScreen = Screen.MODEL_MANAGER }
                        )
                    }
                    Screen.MONITOR -> {
                        if (activeMode != null) {
                            var pipeline by remember { mutableStateOf(SentinelForegroundService.activePipeline) }
                            LaunchedEffect(activeMode) {
                                while (SentinelForegroundService.activePipeline == null) {
                                    kotlinx.coroutines.delay(100)
                                }
                                pipeline = SentinelForegroundService.activePipeline
                            }

                            val statusFlow = pipeline?.status
                                ?: remember { MutableStateFlow(SensePipeline.PipelineStatus()) }
                            val status by statusFlow.collectAsStateWithLifecycle()
                            
                            MonitorScreen(
                                mode = activeMode!!,
                                status = status,
                                onStop = {
                                    stopSentinel()
                                    activeModeState.value = null
                                    currentScreen = Screen.SELECTOR
                                },
                                onTriggerScenario = { scenario ->
                                    SentinelForegroundService.activePipeline?.triggerDemoScenario(scenario)
                                },
                                onOpenLedger = { currentScreen = Screen.FORENSIC_LEDGER }
                            )
                        } else {
                            currentScreen = Screen.SELECTOR
                        }
                    }
                    Screen.FORENSIC_LEDGER -> {
                        val enclaveDir = File(filesDir, "Aegis_Forensic_Enclave")
                        ForensicLedgerScreen(
                            enclaveDir = enclaveDir,
                            onBack = { currentScreen = if (activeMode != null) Screen.MONITOR else Screen.SELECTOR }
                        )
                    }
                    Screen.MODEL_MANAGER -> {
                        ModelManagerScreen(
                            onBack = { currentScreen = Screen.SELECTOR }
                        )
                    }
                }
            }
        }
    }

    private fun startSentinel(mode: OperationalMode, uri: Uri?) {
        val intent = Intent(this, SentinelForegroundService::class.java)
            .putExtra(SentinelForegroundService.EXTRA_MODE, mode.name)
        if (uri != null) {
            intent.putExtra("video_uri", uri.toString())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSentinel() {
        stopService(Intent(this, SentinelForegroundService::class.java))
    }
}
