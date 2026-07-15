package com.aegisedge.os.core.model

import kotlinx.serialization.Serializable

/**
 * Shared data contracts flowing between the 4 pipeline layers.
 * Everything here is deliberately plain-data so any layer can be swapped
 * out (or replayed from a recorded session) without touching its neighbors.
 */

/** A single camera frame as it moves through the pipeline (volatile — RAM only). */
class VideoFrame(
    val timestampNanos: Long,
    /** YUV_420_888 planes packed into a single buffer; never persisted unless Forensic Lock fires. */
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val cameraId: String,
)

/** A chunk of PCM-16 audio aligned to the frame clock (volatile — RAM only). */
class AudioChunk(
    val timestampNanos: Long,
    val pcm: ShortArray,
    val sampleRate: Int = 16_000,
)

// ---------------------------------------------------------------------------
// Layer 1 — Perception Gate
// ---------------------------------------------------------------------------

/** Why the perception gate decided to wake the heavy models. */
enum class WakeTrigger { MOTION, OBJECT_OF_INTEREST, ACOUSTIC_ANOMALY, ACOUSTIC_SILENCE, G_FORCE_SPIKE, MANUAL }

data class WakeSignal(
    val trigger: WakeTrigger,
    val timestampNanos: Long,
    /** Detector label that fired, e.g. "person", "glass_breaking", "dead_silence". */
    val label: String,
    val confidence: Float,
    /** The frame/audio that caused the wake, handed straight to Layer 2. */
    val frame: VideoFrame?,
    val audio: AudioChunk?,
)

// ---------------------------------------------------------------------------
// Layer 2 — Semantic Vision & Scene Graphing
// ---------------------------------------------------------------------------

/** Normalized PaliGemma location tokens: <loc0000>..<loc1023> over a 224x224 grid. */
@Serializable
data class LocBox(val yMin: Int, val xMin: Int, val yMax: Int, val xMax: Int) {
    /** Renders back to PaliGemma token form, e.g. "<loc0320><loc0512><loc0640><loc0768>". */
    fun toLocTokens(): String =
        listOf(yMin, xMin, yMax, xMax).joinToString("") { "<loc%04d>".format(it) }
}

@Serializable
data class SemanticEntity(
    /** Stable per-track id assigned by the scene graph, e.g. "person_A", "backpack_1". */
    val entityId: String,
    val category: String,
    val box: LocBox,
    val attributes: List<String> = emptyList(),
    val confidence: Float,
)

@Serializable
data class SceneSnapshot(
    val timestampNanos: Long,
    val entities: List<SemanticEntity>,
    /** Free-text scene caption from PaliGemma ("two people arguing near turnstile"). */
    val caption: String,
    /** Semantic audio tags produced concurrently ("shouting", "engine_idle", "silence"). */
    val audioTags: List<String>,
    /** Relationship edges materialized from the Vectorized Scene Graph this tick. */
    val relations: List<SceneRelation>,
)

@Serializable
data class SceneRelation(
    val subjectId: String,
    /** OWNS, CARRIES, NEAR, ABANDONED, ENTERED, EXITED, APPROACHING ... */
    val predicate: String,
    val objectId: String,
    val sinceNanos: Long,
)

// ---------------------------------------------------------------------------
// Layer 3 — Neuro-Symbolic Logic Engine
// ---------------------------------------------------------------------------

enum class ThreatLevel { BENIGN, AMBIGUOUS, ELEVATED, VERIFIED_THREAT }

@Serializable
data class ThreatAssessment(
    val timestampNanos: Long,
    val level: String,                       // ThreatLevel.name — kept as String for clean JSON
    val rationale: String,                   // Gemma 3 chain-of-evidence, human-readable
    val triggeredRules: List<String>,
    /** Non-null when Gemma requests more data instead of deciding (Active Probing). */
    val probeRequest: ProbeCommand? = null,
    val worldStateJson: String,              // full "State of the World" ledger at decision time
)

/** A JSON function call emitted by Gemma 3 to change physical sensor state. */
@Serializable
data class ProbeCommand(
    val function: String,                    // e.g. "activate_infrared_floodlight"
    val args: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Layer 4 — Privacy & Forensic Enclave
// ---------------------------------------------------------------------------

enum class PrivacyVerdict { BENIGN_WIPE, THREAT_FORENSIC_LOCK }

@Serializable
data class ForensicManifest(
    val incidentId: String,
    val createdAtUtc: String,
    val mode: String,
    val threatLevel: String,
    val rationale: String,
    val videoFile: String,
    val videoSha256: String,
    val audioFile: String,
    val audioSha256: String,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val worldStateJson: String,
    /** ShieldGemma-redacted event narrative safe for external dispatch. */
    val redactedNarrative: String,
)

/** Pristine JSON alert for Law-Enforcement mode — hashes instead of imagery. */
@Serializable
data class DispatchFlashAlert(
    val incidentId: String,
    val timestampUtc: String,
    val location: String,
    val suspectProfile: List<String>,        // redacted descriptors, never raw video
    val evidenceSha256: List<String>,
    val threatLevel: String,
    val summary: String,
)
