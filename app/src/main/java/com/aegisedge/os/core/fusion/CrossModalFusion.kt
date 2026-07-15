package com.aegisedge.os.core.fusion

import com.aegisedge.os.core.model.SceneSnapshot

/**
 * Cross-Modal Sensor Fusion — pits vision against audio BEFORE the reasoner
 * escalates, killing the classic single-modality false alarms that cause
 * alarm fatigue. Symbolic pre-filter: cheap, deterministic, auditable.
 *
 *   vision "crowd running"  + audio "laughter"  → SUPPRESS (recess, not riot)
 *   vision "person on floor"+ audio "silence"   → CORROBORATE (collapse)
 *   vision "baby asleep"    + audio "barking"   → SUPPRESS (external bleed)
 */
object CrossModalFusion {

    enum class FusionVerdict { CORROBORATED, CONTRADICTED_SUPPRESS, INSUFFICIENT }

    data class FusionResult(
        val verdict: FusionVerdict,
        val rule: String,
        val explanation: String,
    )

    /** (visual predicate, audio predicate, verdict-if-both-match, rule id) */
    private data class Rule(
        val id: String,
        val visual: (SceneSnapshot) -> Boolean,
        val audio: (List<String>) -> Boolean,
        val verdict: FusionVerdict,
        val explanation: String,
    )

    private val rules = listOf(
        Rule(
            id = "CROWD_BUT_LAUGHTER",
            visual = { it.entities.count { e -> e.category.startsWith("person") } >= 4 },
            audio = { tags -> tags.any { it.contains("laugh") || it.contains("cheer") } },
            verdict = FusionVerdict.CONTRADICTED_SUPPRESS,
            explanation = "Dense crowd visually, but audio is laughter/cheering — playful gathering, suppress alarm.",
        ),
        Rule(
            id = "CROWD_AND_SCREAM",
            visual = { it.entities.count { e -> e.category.startsWith("person") } >= 4 },
            audio = { tags -> tags.any { it.contains("scream") || it.contains("shout") || it.contains("gunshot") } },
            verdict = FusionVerdict.CORROBORATED,
            explanation = "Crowd plus distress audio — both modalities agree on threat.",
        ),
        Rule(
            id = "SLEEPING_BABY_EXTERNAL_BLEED",
            visual = { snap ->
                snap.entities.any { it.category == "infant" } &&
                    snap.caption.contains("asleep", ignoreCase = true)
            },
            audio = { tags -> tags.any { it.contains("dog") || it.contains("bark") || it.contains("traffic") || it.contains("siren") } },
            verdict = FusionVerdict.CONTRADICTED_SUPPRESS,
            explanation = "Noise is external room-bleed (dog/traffic) while infant is visually asleep and undisturbed — suppress.",
        ),
        Rule(
            id = "PERSON_DOWN_SILENT",
            visual = { snap ->
                snap.caption.contains("lying", true) || snap.caption.contains("collapsed", true) ||
                    snap.caption.contains("on the ground", true)
            },
            audio = { tags -> tags.isEmpty() || tags.all { it.contains("silence") } },
            verdict = FusionVerdict.CORROBORATED,
            explanation = "Person down with no vocal response — medical emergency corroborated by silence.",
        ),
        Rule(
            id = "GLASS_BREAK_NO_VISUAL",
            visual = { it.entities.none { e -> e.category.startsWith("person") } },
            audio = { tags -> tags.any { it.contains("glass") } },
            verdict = FusionVerdict.INSUFFICIENT,
            explanation = "Glass-break audio with empty frame — request probe (exposure/ISO) before deciding.",
        ),
    )

    fun fuse(snapshot: SceneSnapshot): FusionResult {
        val audioTags = snapshot.audioTags.map { it.lowercase() }
        for (rule in rules) {
            if (rule.visual(snapshot) && rule.audio(audioTags)) {
                return FusionResult(rule.verdict, rule.id, rule.explanation)
            }
        }
        return FusionResult(
            FusionVerdict.INSUFFICIENT, "NO_RULE",
            "No cross-modal rule matched; defer to Gemma 3 reasoning."
        )
    }
}
