package com.aegisedge.os.core.probing

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aegisedge.os.core.model.ProbeCommand
import com.aegisedge.os.core.sensors.AudioCaptureManager
import com.aegisedge.os.core.sensors.CameraController

/**
 * Active Probing — Gemma 3 is allowed to CHANGE the physical sensor state
 * when it is ambiguous instead of guessing. The reasoner emits a JSON
 * function call (see [GemmaReasonerEngine.OUTPUT_CONTRACT]); this dispatcher
 * validates it against a fixed allowlist and executes the hardware action.
 *
 * Example round-trip:
 *   Gemma: {"function_call":{"function":"camera2_set_exposure_compensation",
 *           "args":{"value":"+2_EV_infrared_flash_pulse"}}}
 *   → exposure bumped +2 EV and an IR pulse fired → next frame re-enters L2.
 */
interface SensorActuator {
    /** Executes the probe; returns a human-readable result fed back into the next prompt. */
    suspend fun execute(command: ProbeCommand): String
}

class ActiveProbingDispatcher(
    private val context: Context,
    private val camera: CameraController,
    private val audio: AudioCaptureManager,
) : SensorActuator {

    /** Fixed allowlist: the LLM can never invent a new hardware capability. */
    private val allowedFunctions = setOf(
        "activate_infrared_floodlight",
        "focus_directional_microphone_zone",
        "camera2_set_exposure_compensation",
        "camera2_set_iso",
        "switch_to_dual_camera",
        "trigger_local_vibration_alert",
    )

    override suspend fun execute(command: ProbeCommand): String {
        if (command.function !in allowedFunctions) {
            return "PROBE_REJECTED: '${command.function}' is not an allowed actuator"
        }
        return when (command.function) {
            "activate_infrared_floodlight" -> {
                val ms = command.args["duration_ms"]?.toLongOrNull() ?: 350L
                camera.pulseInfraredFloodlight(ms)
                "PROBE_OK: IR floodlight pulsed ${ms}ms — re-evaluate next frame for sub-blanket motion"
            }
            "focus_directional_microphone_zone" -> {
                val zone = command.args["zone"] ?: "center"
                audio.focusDirectionalZone(zone)
                "PROBE_OK: microphone beam focused on zone '$zone'"
            }
            "camera2_set_exposure_compensation" -> {
                // Accepts "+2", "2", or the composite "+2_EV_infrared_flash_pulse" form.
                val rawArg = command.args["value"] ?: "+1"
                val ev = Regex("[+-]?\\d+").find(rawArg)?.value?.toIntOrNull() ?: 1
                camera.setExposureCompensation(ev)
                val pulsed = rawArg.contains("infrared", ignoreCase = true) ||
                    rawArg.contains("flash_pulse", ignoreCase = true)
                if (pulsed) camera.pulseInfraredFloodlight()
                "PROBE_OK: exposure ${if (ev >= 0) "+" else ""}$ev EV" +
                    if (pulsed) " with IR flash pulse" else ""
            }
            "camera2_set_iso" -> {
                val iso = command.args["iso"]?.toIntOrNull() ?: 1600
                camera.setManualIso(iso)
                "PROBE_OK: sensor ISO forced to $iso for low-light platform"
            }
            "switch_to_dual_camera" -> {
                val ok = camera.engageDualCamera()
                if (ok) "PROBE_OK: cabin camera engaged — driver frames now streaming"
                else "PROBE_FAILED: no secondary camera on this device"
            }
            "trigger_local_vibration_alert" -> {
                vibrate()
                // TODO(hardware): also fan out over BLE GATT / Wi-Fi Direct to a
                // paired wearable — the baby-monitor silent-SIDS alert path.
                "PROBE_OK: local vibration alert fired (BLE fan-out pending pairing)"
            }
            else -> "PROBE_REJECTED"
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 800), -1))
    }
}
