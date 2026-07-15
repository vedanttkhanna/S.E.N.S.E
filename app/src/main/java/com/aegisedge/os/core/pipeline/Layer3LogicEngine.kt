package com.aegisedge.os.core.pipeline

import com.aegisedge.os.core.fusion.AcousticCounterfactualEngine
import com.aegisedge.os.core.fusion.CrossModalFusion
import com.aegisedge.os.core.inference.GemmaReasonerEngine
import com.aegisedge.os.core.model.SceneSnapshot
import com.aegisedge.os.core.model.ThreatAssessment
import com.aegisedge.os.core.model.ThreatLevel
import com.aegisedge.os.core.probing.SensorActuator
import com.aegisedge.os.core.sensors.MotionTelemetry
import com.aegisedge.os.modes.ModeRuleSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LAYER 3 — Neuro-Symbolic Logic Engine.
 *
 * The SYMBOLIC half runs first and free: cross-modal fusion rules and the
 * acoustic counterfactual verdict. Their conclusions are injected as hard
 * facts into the NEURAL half — quantized Gemma 3 — which evaluates the active
 * mode's rules against the "State of the World" JSON ledger it maintains in
 * RAM across ticks.
 *
 * If Gemma answers with a function_call instead of a verdict, the probe is
 * executed via [SensorActuator] and the probe result is queued as an
 * observation for the next tick (evidence-gathering loop, max 2 hops).
 */
class Layer3LogicEngine(
    private val reasoner: GemmaReasonerEngine,
    private val counterfactuals: AcousticCounterfactualEngine,
    private val actuator: SensorActuator,
    private val telemetry: MotionTelemetry,
    private val ruleSet: ModeRuleSet,
) {

    /** The RAM-resident "State of the World" ledger. Wiped on benign verdicts. */
    @Volatile
    var worldState: String = "{}"
        private set

    private var pendingProbeResult: String? = null
    private var probeHopsThisIncident = 0

    suspend fun evaluate(snapshot: SceneSnapshot, rmsEnergy: Double): ThreatAssessment {
        reasoner.awake()

        // ---- SYMBOLIC pre-pass -------------------------------------------
        val fusion = CrossModalFusion.fuse(snapshot)
        if (fusion.verdict == CrossModalFusion.FusionVerdict.CONTRADICTED_SUPPRESS) {
            // Deterministic suppression: don't even spend Gemma tokens on it.
            return ThreatAssessment(
                snapshot.timestampNanos, ThreatLevel.BENIGN.name,
                "Cross-modal suppression [${fusion.rule}]: ${fusion.explanation}",
                listOf(fusion.rule), probeRequest = null, worldStateJson = worldState,
            )
        }

        val cf = counterfactuals.observe(
            snapshot.timestampNanos,
            snapshot.audioTags.map { com.aegisedge.os.core.inference.AudioTag(it, 1f) },
            rmsEnergy,
        )

        // ---- NEURAL pass --------------------------------------------------
        val observations = buildJsonObject {
            put("scene_caption", snapshot.caption)
            put("entities", snapshot.entities.joinToString { "${it.entityId}:${it.category}@${it.box.toLocTokens()}" })
            put("relations", snapshot.relations.joinToString { "${it.subjectId} [${it.predicate}] ${it.objectId}" })
            put("audio_tags", snapshot.audioTags.joinToString())
            put("fusion_verdict", "${fusion.verdict} (${fusion.rule}): ${fusion.explanation}")
            put("acoustic_counterfactual", cf.description)
            put("counterfactual_anomaly", cf.anomaly)
            put("current_g_force", telemetry.currentGForce.value)
            pendingProbeResult?.let { put("active_probe_result", it) }
        }.let { Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
        pendingProbeResult = null

        val out = reasoner.evaluate(ruleSet.gemmaSystemRules, worldState, observations)
        worldState = out.updatedWorldState

        // ---- Active Probing loop -------------------------------------------
        if (out.probe != null && ruleSet.usesActiveProbing && probeHopsThisIncident < MAX_PROBE_HOPS) {
            probeHopsThisIncident++
            pendingProbeResult = actuator.execute(out.probe)
            return ThreatAssessment(
                snapshot.timestampNanos, ThreatLevel.AMBIGUOUS.name,
                "Probing for evidence: ${out.probe.function} → $pendingProbeResult",
                out.triggeredRules, probeRequest = out.probe, worldStateJson = worldState,
            )
        }
        if (out.threatLevel == ThreatLevel.BENIGN.name) probeHopsThisIncident = 0

        return ThreatAssessment(
            snapshot.timestampNanos, out.threatLevel, out.rationale,
            out.triggeredRules, probeRequest = null, worldStateJson = worldState,
        )
    }

    /** Called by Layer 4 on a benign wipe — the ledger is volatile evidence too. */
    fun wipeWorldState() {
        worldState = "{}"
        probeHopsThisIncident = 0
        counterfactuals.reset()
    }

    private companion object { const val MAX_PROBE_HOPS = 2 }
}
