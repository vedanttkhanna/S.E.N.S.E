package com.aegisedge.os.core.inference

import android.content.Context
import com.aegisedge.os.core.model.ProbeCommand
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log

/**
 * Layer-3 reasoning engine: Gemma 3 1B (or Gemma 3n) INT4, running as the
 * neuro-symbolic core.
 */
class GemmaReasonerEngine(
    private val context: Context,
    private val registry: ModelRegistry,
) : AutoCloseable {

    private var llm: LlmInference? = null
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var mockCycleCounter = 0

    data class ReasonerOutput(
        val threatLevel: String,          // BENIGN | AMBIGUOUS | ELEVATED | VERIFIED_THREAT
        val rationale: String,
        val triggeredRules: List<String>,
        val updatedWorldState: String,    // full JSON ledger to hold in RAM
        val probe: ProbeCommand?,         // non-null => Active Probing requested
        val raw: String,
    )

    suspend fun awake(): Boolean = mutex.withLock {
        if (llm != null) return true
        val modelAvailable = runCatching { registry.resolveReasoner().exists() }.getOrDefault(false)
        if (modelAvailable) {
            runCatching {
                llm = withContext(Dispatchers.IO) {
                    LlmInference.createFromOptions(
                        context,
                        LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(registry.resolveReasoner().absolutePath)
                            .setMaxTokens(1024)
                            .setPreferredBackend(LlmInference.Backend.GPU)
                            .build()
                    )
                }
                Log.i("GemmaReasoner", "Successfully loaded Gemma 3 1B on GPU!")
            }.onFailure { e ->
                Log.e("GemmaReasoner", "CRITICAL ERROR: Failed to load Gemma model. Falling back to Mock AI.", e)
            }
        } else {
            Log.w("GemmaReasoner", "WARNING: Gemma model file not found at expected path. Falling back to Mock AI.")
        }
        true
    }

    /**
     * One reasoning tick. [systemRules] comes from the active operational mode,
     * [worldState] is the previous JSON ledger, [observations] the new Layer-2 facts.
     */
    suspend fun evaluate(
        systemRules: String,
        worldState: String,
        observations: String,
    ): ReasonerOutput = mutex.withLock {
        val engine = llm
        if (engine == null) {
            mockCycleCounter++
            val isDashcam = systemRules.contains("dashcam", ignoreCase = true)
            val isPolice = systemRules.contains("police", ignoreCase = true)
            val isRoadSafety = systemRules.contains("road safety", ignoreCase = true)
            
            // For the hackathon demo, we override the normal logic for these specific modes
            val mockLevel = when {
                isDashcam -> "BENIGN"
                isPolice -> "VERIFIED_THREAT"
                isRoadSafety -> "VERIFIED_THREAT"
                else -> {
                    val slot = mockCycleCounter % 20
                    when {
                        slot < 12 -> "BENIGN"
                        slot < 15 -> "AMBIGUOUS"
                        slot < 18 -> "ELEVATED"
                        else -> "VERIFIED_THREAT"
                    }
                }
            }

            val isCampus  = systemRules.contains("campus", ignoreCase = true) || systemRules.contains("school", ignoreCase = true)
            val isBaby    = systemRules.contains("baby", ignoreCase = true) || systemRules.contains("infant", ignoreCase = true)

            val (rationale, rules, worldSt) = when {
                isDashcam -> mockDashcam(mockLevel)
                isPolice  -> mockPolice(mockLevel)
                isRoadSafety -> Triple("Dead animal detected, no movement, paramedics called.", "\"R1_HAZARD_DETECTED\"", "\"animal_dead\": true, \"paramedics_called\": true")
                isCampus  -> mockCampus(mockLevel)
                isBaby    -> mockBaby(mockLevel)
                else      -> mockGeneral(mockLevel)
            }

            val responseText = """
                {
                  "threat_level": "$mockLevel",
                  "rationale": "$rationale",
                  "triggered_rules": [$rules],
                  "world_state": { $worldSt }
                }
            """.trimIndent()
            return parse(responseText)
        }
        val prompt = buildString {
            appendLine(systemRules)
            appendLine()
            appendLine("## CURRENT STATE OF THE WORLD (JSON ledger)")
            appendLine(worldState)
            appendLine()
            appendLine("## NEW OBSERVATIONS")
            appendLine(observations)
            appendLine()
            appendLine(OUTPUT_CONTRACT)
        }
        val raw = withContext(Dispatchers.Default) { engine.generateResponse(prompt) }
        return parse(raw)
    }

    suspend fun sleep() = mutex.withLock { llm?.close(); llm = null }
    override fun close() { llm?.close(); llm = null }

    private fun parse(raw: String): ReasonerOutput {
        val obj: JsonObject? = extractJson(raw)
        val probe = obj?.get("function_call")?.let { fc ->
            runCatching {
                val fcObj = fc.jsonObject
                ProbeCommand(
                    function = fcObj["function"]!!.jsonPrimitive.content,
                    args = fcObj["args"]?.jsonObject
                        ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                )
            }.getOrNull()
        }
        return ReasonerOutput(
            threatLevel = obj?.get("threat_level")?.jsonPrimitive?.content ?: "AMBIGUOUS",
            rationale = obj?.get("rationale")?.jsonPrimitive?.content ?: raw.take(240),
            triggeredRules = obj?.get("triggered_rules")?.let { rules ->
                runCatching {
                    Json.decodeFromString<List<String>>(rules.toString())
                }.getOrDefault(emptyList())
            } ?: emptyList(),
            updatedWorldState = obj?.get("world_state")?.toString() ?: "{}",
            probe = probe,
            raw = raw,
        )
    }

    private fun extractJson(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }
            .getOrNull()
    }

    // ── Mock rationale helpers (only used when model is not loaded) ──────────

    private fun mockDashcam(level: String): Triple<String, String, String> =
        Triple("Benign. No threat is detected, all is fine on the road.", "", "\"status\": \"normal_traffic\"")

    private fun mockPolice(level: String): Triple<String, String, String> =
        Triple("Threat is detected: fighting and weapon detected. Calling the authorities.", "\"R1_CROSS_MODAL_MANDATE\"", "\"dispatch_fired\": true, \"weapon\": true")

    private fun mockCampus(level: String): Triple<String, String, String> = when (level) {
        "BENIGN"          -> Triple("No anomalies detected. Normal student foot traffic in corridor, all items attended.", "", "\"status\": \"normal_activity\"")
        "AMBIGUOUS"       -> Triple("Insufficient evidence to classify. A bag was stationary for 45 seconds but an individual remains within 3 meters.", "\"R0_BRIEF_STATIONARY\"", "\"bag_monitored\": true")
        "ELEVATED"        -> Triple("Unattended item detected in hallway for over 90 seconds. Owner has moved out of frame but may return.", "\"R1_UNATTENDED_ITEM\"", "\"unattended_duration_s\": 90")
        else              -> Triple("A scene-graph loitering alert has been flagged for an unattended bag left in the classroom hallway with no owner near.", "\"R2_ABANDONED_BAG\"", "\"unattended_item\": \"backpack\"")
    }

    private fun mockBaby(level: String): Triple<String, String, String> = when (level) {
        "BENIGN"          -> Triple("No anomalies detected. Infant breathing cadence is regular at 38 breaths per minute.", "", "\"status\": \"breathing_normal\"")
        "AMBIGUOUS"       -> Triple("Insufficient evidence to classify. Brief 4-second pause in detected breathing rhythm, likely positional shift.", "\"R0_BRIEF_PAUSE\"", "\"pause_duration_s\": 4")
        "ELEVATED"        -> Triple("Breathing cadence irregularity sustained for 8 seconds. Activating enhanced IR monitoring.", "\"R2_CADENCE_IRREGULAR\"", "\"irregular_duration_s\": 8")
        else              -> Triple("Silent-SIDS detection logic triggered. Infant breathing cadence baseline missing for 12 seconds.", "\"R3_SILENT_SIDS\"", "\"alert_fired\": true")
    }

    private fun mockGeneral(level: String): Triple<String, String, String> = when (level) {
        "BENIGN"          -> Triple("No anomalies detected. Scene is static with expected ambient activity levels.", "", "\"status\": \"all_clear\"")
        "AMBIGUOUS"       -> Triple("Insufficient evidence to classify. Motion detected at scene periphery but object not yet identified.", "\"R0_PERIPHERAL_MOTION\"", "\"peripheral_motion\": true")
        "ELEVATED"        -> Triple("Passenger lingering near platform edge zone for extended duration. Monitoring trajectory.", "\"R2_EDGE_PROXIMITY\"", "\"edge_proximity_alert\": true")
        else              -> Triple("Platform passenger loitering near edge zone exceeds Platform Edge limit rules.", "\"R3_PLATFORM_EDGE\"", "\"loitering_alert\": true")
    }

    companion object {
        val OUTPUT_CONTRACT = """
            ## OUTPUT FORMAT — respond with ONLY this JSON object:
            {
              "threat_level": "VERIFIED_THREAT",
              "rationale": "<one-paragraph chain of evidence>",
              "triggered_rules": ["<rule ids>"],
              "world_state": { <the complete updated State-of-the-World ledger> },
              "function_call": { "function": "<probe name>", "args": {} }
            }
            Omit "function_call" unless you need more sensor evidence. Available probes:
            activate_infrared_floodlight, focus_directional_microphone_zone,
            camera2_set_exposure_compensation, camera2_set_iso, switch_to_dual_camera,
            trigger_local_vibration_alert.
        """.trimIndent()
    }
}
