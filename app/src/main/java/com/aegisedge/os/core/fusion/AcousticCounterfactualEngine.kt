package com.aegisedge.os.core.fusion

import com.aegisedge.os.core.inference.AudioTag
import java.util.ArrayDeque

/**
 * Acoustic Counterfactuals — anomaly by ABSENCE.
 *
 * Classic classifiers only fire on sounds that exist. This engine learns the
 * expected ambient baseline (infant breathing cadence, campus chatter, metro
 * rumble) and raises an anomaly when expected tokens go MISSING:
 *
 *   Baby monitor : breathing tokens vanish while video shows infant present
 *                  → silent-SIDS candidate.
 *   School mode  : sustained chatter drops to dead silence in <2s
 *                  → crowd-scatter signature (precedes screaming by seconds).
 */
class AcousticCounterfactualEngine(
    /** Tokens this mode EXPECTS to hear continuously, from the active ruleset. */
    private val expectedTokens: Set<String>,
    /** Seconds of missing-expected-audio before an anomaly fires. */
    private val silenceToleranceSec: Double = 8.0,
    /** RMS below this counts as "dead silence" regardless of classifier output. */
    private val deadSilenceRms: Double = 0.004,
    private val windowChunks: Int = 40,   // 20 s of 500 ms chunks
) {

    data class CounterfactualVerdict(
        val anomaly: Boolean,
        val missingTokens: Set<String>,
        val deadSilenceSeconds: Double,
        val baselineEstablished: Boolean,
        val description: String,
    )

    private class Observation(val tags: Set<String>, val rms: Double, val tNanos: Long)

    private val window = ArrayDeque<Observation>(windowChunks)
    private var baselineHits = 0
    private var baselineEstablished = false
    private var silenceStartedNanos: Long? = null

    /** Feed one classified audio chunk per tick. */
    fun observe(timestampNanos: Long, tags: List<AudioTag>, rmsEnergy: Double): CounterfactualVerdict {
        val labels = tags.map { it.label.lowercase() }.toSet()
        if (window.size >= windowChunks) window.pollFirst()
        window.addLast(Observation(labels, rmsEnergy, timestampNanos))

        // Baseline: we must actually HEAR the expected tokens for a while before
        // their absence means anything — otherwise an empty room false-fires.
        val hearingExpected = expectedTokens.any { exp -> labels.any { it.contains(exp) } }
        if (hearingExpected) {
            baselineHits++
            if (baselineHits >= BASELINE_CONFIRMATIONS) baselineEstablished = true
            silenceStartedNanos = null
        }

        val isDeadSilent = rmsEnergy < deadSilenceRms && labels.none { it in IGNORABLE }
        val missing = if (baselineEstablished && !hearingExpected)
            expectedTokens.filter { exp -> labels.none { it.contains(exp) } }.toSet()
        else emptySet()

        if ((isDeadSilent || missing.isNotEmpty()) && silenceStartedNanos == null) {
            silenceStartedNanos = timestampNanos
        } else if (!isDeadSilent && missing.isEmpty()) {
            silenceStartedNanos = null
        }

        val silentFor = silenceStartedNanos
            ?.let { (timestampNanos - it) / 1e9 } ?: 0.0
        val anomaly = baselineEstablished && silentFor >= silenceToleranceSec

        return CounterfactualVerdict(
            anomaly = anomaly,
            missingTokens = missing,
            deadSilenceSeconds = silentFor,
            baselineEstablished = baselineEstablished,
            description = when {
                anomaly && missing.isNotEmpty() ->
                    "Expected ambient tokens ${missing.joinToString()} absent for " +
                        "%.1fs after established baseline".format(silentFor)
                anomaly -> "Environment dropped to dead silence for %.1fs".format(silentFor)
                else -> "ambient nominal"
            },
        )
    }

    fun reset() {
        window.clear(); baselineHits = 0
        baselineEstablished = false; silenceStartedNanos = null
    }

    private companion object {
        const val BASELINE_CONFIRMATIONS = 20        // ~10 s of confirmed baseline
        val IGNORABLE = setOf("silence", "white noise", "static")
    }
}
