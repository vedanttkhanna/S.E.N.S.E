package com.aegisedge.os.core.pipeline

import com.aegisedge.os.core.graph.VectorizedSceneGraph
import com.aegisedge.os.core.inference.ShieldGemmaEngine
import com.aegisedge.os.core.memory.MemoryManager
import com.aegisedge.os.core.model.DispatchFlashAlert
import com.aegisedge.os.core.model.ForensicManifest
import com.aegisedge.os.core.model.PrivacyVerdict
import com.aegisedge.os.core.model.ThreatAssessment
import com.aegisedge.os.core.model.ThreatLevel
import com.aegisedge.os.core.sensors.MotionTelemetry
import com.aegisedge.os.modes.OperationalMode
import java.time.Instant

/**
 * LAYER 4 — Privacy & Forensic Enclave Lock. The final arbiter.
 *
 * Every text log from Layer 3 passes through ShieldGemma 2B:
 *
 *  BENIGN (or audit fails)  → the RAM ring buffer, scene graph and world-state
 *                             ledger are wiped and System.gc() is invoked.
 *                             The event never existed.
 *  VERIFIED_THREAT (upheld) → Forensic Lock: RAM buffer dumped to .mp4/.wav,
 *                             SHA-256 hashed, ShieldGemma-redacted manifest
 *                             written, ledger entry chained. Optionally a
 *                             Dispatch Flash Alert is produced for LE mode.
 */
class Layer4PrivacyEnclave(
    private val shieldGemma: ShieldGemmaEngine,
    private val memory: MemoryManager,
    private val sceneGraph: VectorizedSceneGraph,
    private val logicEngine: Layer3LogicEngine,
    private val telemetry: MotionTelemetry,
    private val mode: OperationalMode,
) {

    data class EnclaveOutcome(
        val verdict: PrivacyVerdict,
        val lockResult: MemoryManager.LockResult? = null,
        val dispatchAlert: DispatchFlashAlert? = null,
        val note: String,
    )

    suspend fun arbitrate(assessment: ThreatAssessment): EnclaveOutcome {
        if (assessment.level != ThreatLevel.VERIFIED_THREAT.name) {
            return benignWipe("Layer 3 verdict ${assessment.level} — nothing to preserve")
        }

        shieldGemma.awake()
        val audit = shieldGemma.audit(assessment.rationale, assessment.worldStateJson)
        if (!audit.upheld) {
            return benignWipe("ShieldGemma overruled the threat: ${audit.reason}")
        }

        // ---- FORENSIC LOCK -------------------------------------------------
        val redacted = shieldGemma.redact(assessment.rationale)
        val fix = telemetry.currentFix()
        val lock = memory.forensicLock { videoSha, audioSha, videoFile, audioFile ->
            ForensicManifest(
                incidentId = "pending",   // MemoryManager assigns the real id in the dir name
                createdAtUtc = Instant.now().toString(),
                mode = mode.name,
                threatLevel = assessment.level,
                rationale = assessment.rationale,
                videoFile = videoFile, videoSha256 = videoSha,
                audioFile = audioFile, audioSha256 = audioSha,
                gpsLat = fix?.lat, gpsLon = fix?.lon,
                worldStateJson = assessment.worldStateJson,
                redactedNarrative = redacted,
            )
        }

        val alert = if (mode.ruleSet.emitsDispatchAlerts) {
            DispatchFlashAlert(
                incidentId = lock.incidentDir.name,
                timestampUtc = Instant.now().toString(),
                location = fix?.let { "%.5f,%.5f".format(it.lat, it.lon) } ?: "unknown",
                suspectProfile = extractDescriptors(redacted),
                evidenceSha256 = listOf(lock.videoSha256, lock.audioSha256),
                threatLevel = assessment.level,
                summary = redacted,
            )
        } else null

        return EnclaveOutcome(
            PrivacyVerdict.THREAT_FORENSIC_LOCK, lock, alert,
            "Forensic Lock complete → ${lock.incidentDir.absolutePath}",
        )
    }

    private fun benignWipe(reason: String): EnclaveOutcome {
        memory.wipe()               // ring buffer gone + System.gc() inside
        sceneGraph.wipe()           // entity tracks gone
        logicEngine.wipeWorldState()// JSON ledger gone
        return EnclaveOutcome(PrivacyVerdict.BENIGN_WIPE, note = reason)
    }

    /** Pulls short clothing/direction descriptors out of the redacted narrative. */
    private fun extractDescriptors(narrative: String): List<String> =
        narrative.split('.', ';').map { it.trim() }.filter { it.isNotEmpty() }.take(5)
}
