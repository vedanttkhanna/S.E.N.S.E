package com.aegisedge.os.core.pipeline

import com.aegisedge.os.core.inference.AcousticScreener
import com.aegisedge.os.core.inference.NanoYoloDetector
import com.aegisedge.os.core.model.AudioChunk
import com.aegisedge.os.core.model.VideoFrame
import com.aegisedge.os.core.model.WakeSignal
import com.aegisedge.os.core.model.WakeTrigger
import com.aegisedge.os.core.sensors.FrameConverter
import com.aegisedge.os.core.sensors.MotionTelemetry
import com.aegisedge.os.modes.ModeRuleSet
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.abs

/**
 * LAYER 1 — Perception Gate (ultra-low power).
 *
 * The "wake-word" of the whole OS: only Nano-YOLO, the audio classifier and
 * raw IMU math run here, over a 1-2 fps stream. Until this layer emits a
 * [WakeSignal], PaliGemma / Gemma 3 / ShieldGemma stay completely unloaded —
 * zero GPU memory, zero battery draw beyond the ISP idling.
 */
class Layer1PerceptionGate(
    private val yolo: NanoYoloDetector,
    private val screener: AcousticScreener,
    private val ruleSet: ModeRuleSet,
) {
    private val _wakes = MutableSharedFlow<WakeSignal>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wakes: SharedFlow<WakeSignal> = _wakes

    /** Simple luminance-delta motion detector — cheaper than any model. */
    private var lastLumaMean = -1.0
    private var lastAudioLabels = emptySet<String>()

    fun screenFrame(frame: VideoFrame) {
        // 1. Free motion check first: mean-luma delta.
        val lumaMean = frame.data.asSequence()
            .take(frame.width * frame.height).map { it.toInt() and 0xFF }.average()
        val motion = lastLumaMean >= 0 && abs(lumaMean - lastLumaMean) > MOTION_LUMA_DELTA
        lastLumaMean = lumaMean
        if (!motion && lastLumaMean >= 0) return   // scene static — YOLO not even needed

        // 2. Motion confirmed: one cheap Nano-YOLO pass.
        val detections = yolo.detect(FrameConverter.toMpImage(frame))
        val hit = detections.firstOrNull { it.label.lowercase() in ruleSet.wakeObjectLabels }
        if (hit != null) {
            _wakes.tryEmit(
                WakeSignal(WakeTrigger.OBJECT_OF_INTEREST, frame.timestampNanos,
                    hit.label, hit.confidence, frame, null)
            )
        } else if (motion) {
            _wakes.tryEmit(
                WakeSignal(WakeTrigger.MOTION, frame.timestampNanos,
                    "motion", 1f, frame, null)
            )
        }
    }

    fun screenAudio(chunk: AudioChunk) {
        val tags = screener.classify(chunk)
        val labels = tags.map { it.label.lowercase() }.toSet()

        // Anomalous NEW sound (glass, scream, gunshot, alarm, crying...)
        val anomalous = tags.firstOrNull { t ->
            ANOMALY_KEYWORDS.any { t.label.lowercase().contains(it) }
        }
        if (anomalous != null) {
            _wakes.tryEmit(
                WakeSignal(WakeTrigger.ACOUSTIC_ANOMALY, chunk.timestampNanos,
                    anomalous.label, anomalous.confidence, null, chunk)
            )
        }

        // Sudden loss of ALL expected ambient tokens: cheap pre-check that lets
        // the full counterfactual engine (Layer 3 input) wake the pipeline.
        val hadExpected = ruleSet.expectedAudioTokens.any { e -> lastAudioLabels.any { it.contains(e) } }
        val hasExpected = ruleSet.expectedAudioTokens.any { e -> labels.any { it.contains(e) } }
        if (hadExpected && !hasExpected && screener.rmsEnergy(chunk) < DEAD_SILENCE_RMS) {
            _wakes.tryEmit(
                WakeSignal(WakeTrigger.ACOUSTIC_SILENCE, chunk.timestampNanos,
                    "dead_silence", 1f, null, chunk)
            )
        }
        lastAudioLabels = labels
    }

    fun screenGForce(event: MotionTelemetry.GForceEvent) {
        if (event.gForce >= ruleSet.gForceWakeThreshold) {
            _wakes.tryEmit(
                WakeSignal(WakeTrigger.G_FORCE_SPIKE, event.timestampNanos,
                    "g_spike_%.1f".format(event.gForce), 1f, null, null)
            )
        }
    }

    private companion object {
        const val MOTION_LUMA_DELTA = 6.0
        const val DEAD_SILENCE_RMS = 0.004
        val ANOMALY_KEYWORDS = setOf(
            "glass", "scream", "shout", "gunshot", "gun", "explosion", "alarm",
            "crying", "crash", "siren", "breaking",
        )
    }
}
