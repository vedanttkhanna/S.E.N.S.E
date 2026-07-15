package com.aegisedge.os.core.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Layer-4 privacy filter: ShieldGemma 2B (INT4).
 */
class ShieldGemmaEngine(
    private val context: Context,
    private val registry: ModelRegistry,
) : AutoCloseable {

    private var llm: LlmInference? = null
    private val mutex = Mutex()

    data class AuditResult(val upheld: Boolean, val reason: String)

    suspend fun awake(): Boolean = mutex.withLock {
        if (llm != null) return true
        if (registry.isAvailable(ModelRegistry.Names.SHIELDGEMMA_2B)) {
            llm = withContext(Dispatchers.IO) {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(registry.resolve(ModelRegistry.Names.SHIELDGEMMA_2B).absolutePath)
                        .setMaxTokens(512)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                )
            }
        }
        true
    }

    suspend fun audit(threatRationale: String, worldStateJson: String): AuditResult = mutex.withLock {
        val engine = llm
        if (engine == null) {
            // Mock audit result for demo — approve if rationale mentions genuine threat keywords
            val lowerRationale = threatRationale.lowercase()
            val isGenuineThreat = listOf("weapon", "collision", "fraud", "sids", "breathing", "unattended", "abandoned", "assault", "loitering", "aggression").any { it in lowerRationale }
            return if (isGenuineThreat) {
                AuditResult(true, "Audit upheld: Event rationale references a recognized public-safety scenario.")
            } else {
                AuditResult(false, "Audit denied: Rationale does not meet the public-safety evidence threshold.")
            }
        }
        val raw = withContext(Dispatchers.Default) {
            engine.generateResponse(
                """
                You are a privacy and safety auditor for an autonomous edge camera.
                A reasoning model flagged the following event as a verified threat.
                Answer YES only if the evidence describes a genuine public-safety
                event justifying local forensic recording. Answer NO if it is
                ordinary private activity, ambiguous, or privacy-invasive.

                RATIONALE: $threatRationale
                WORLD STATE: $worldStateJson

                Answer with exactly one line: YES: <reason> or NO: <reason>
                """.trimIndent()
            )
        }
        val upheld = raw.trim().uppercase().startsWith("YES")
        AuditResult(upheld, raw.substringAfter(':').trim())
    }

    suspend fun redact(narrative: String): String = mutex.withLock {
        val engine = llm
        if (engine == null) {
            // Mock redaction output for demo — strip PII-like tokens from the narrative
            val cleaned = narrative
                .replace(Regex("[A-Z][a-z]+ [A-Z][a-z]+"), "[NAME REDACTED]")
                .replace(Regex("\\b[A-Z]{2,3}[- ]?\\d{3,4}[- ]?[A-Z]{0,3}\\b"), "[PLATE REDACTED]")
                .replace(Regex("\\b\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"), "[PHONE REDACTED]")
            return "[REDACTED] $cleaned"
        }
        withContext(Dispatchers.Default) {
            engine.generateResponse(
                """
                Rewrite the incident narrative below for an official dispatch alert.
                Remove all personally identifying information: names, exact faces
                descriptions, license plates, spoken content, phone numbers.
                Keep actionable descriptors (clothing color, direction of travel,
                object types, counts). Output only the rewritten narrative.

                NARRATIVE: $narrative
                """.trimIndent()
            ).trim()
        }
    }

    suspend fun sleep() = mutex.withLock { llm?.close(); llm = null }
    override fun close() { llm?.close(); llm = null }
}
