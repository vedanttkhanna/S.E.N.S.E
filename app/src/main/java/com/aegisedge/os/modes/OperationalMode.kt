package com.aegisedge.os.modes

/**
 * The 5 operational modes. Each mode is pure configuration: a Gemma 3 system
 * ruleset, the acoustic-counterfactual baseline, gate thresholds, and feature
 * flags. The pipeline itself is mode-agnostic — selecting a mode never
 * changes code paths, only the symbolic rules the reasoner runs under.
 */
enum class OperationalMode(val ruleSet: ModeRuleSet) {

    SMART_DASHCAM(
        ModeRuleSet(
            displayName = "Smart Dashcam",
            tagline = "Crash-for-cash fraud & driver medical emergency detection",
            emoji = "🚗",
            gemmaSystemRules = """
                You are the reasoning core of a vehicle dashcam on a $150 phone.
                RULES:
                R1_CRASH: A G-force spike over 2.5g with a forward-closing trajectory
                  in the PaliGemma log is a genuine collision → VERIFIED_THREAT.
                R2_CRASH_FOR_CASH: A G-force spike where the LEAD vehicle's trajectory
                  log shows REVERSING motion immediately before impact is staged
                  "crash-for-cash" fraud → VERIFIED_THREAT, tag rationale FRAUD_REVERSING.
                R3_POTHOLE: A vertical-axis-only spike with unchanged vehicle trajectories
                  is road surface, not a collision → BENIGN.
                R4_DRIVER_MEDICAL: Cabin camera shows head droop / slumped posture AND
                  cabin audio is silent (no speech, no radio response) for over 10s
                  while the vehicle moves → VERIFIED_THREAT, request switch_to_dual_camera
                  first if cabin stream is not active.
            """.trimIndent(),
            expectedAudioTokens = setOf("engine", "vehicle", "speech"),
            gForceWakeThreshold = 2.5f,
            usesDualCamera = true,
            usesGpsTelemetry = true,
            wakeObjectLabels = setOf("car", "truck", "person", "motorcycle", "bicycle"),
        )
    ),

    LAW_ENFORCEMENT(
        ModeRuleSet(
            displayName = "Law Enforcement / Traffic Police",
            tagline = "Cross-modal verified alerts, JSON dispatch — no heatmaps",
            emoji = "🚨",
            gemmaSystemRules = """
                You are the reasoning core of a police-deployed monitoring unit.
                RULES:
                R1_CROSS_MODAL_MANDATE: NEVER escalate on a single modality. A visual
                  threat cue (weapon, fight posture, fleeing) requires an agreeing
                  acoustic cue (shouting, gunshot, alarm) or an explicit fusion
                  CORROBORATED verdict. Contradiction → BENIGN.
                R2_DISPATCH_FORMAT: On VERIFIED_THREAT, your world_state must include a
                  "dispatch" object: suspect descriptors (clothing, direction, count —
                  never identity guesses), location context, and evidence hash slots.
                R3_NO_BIOMETRICS: Never describe faces or infer identity, ethnicity,
                  or age. Descriptors are limited to clothing, carried objects, and
                  direction of travel.
            """.trimIndent(),
            expectedAudioTokens = setOf("traffic", "speech", "vehicle"),
            emitsDispatchAlerts = true,
            wakeObjectLabels = setOf("person", "car", "knife", "gun", "motorcycle"),
        )
    ),

    SCHOOL_SECURITY(
        ModeRuleSet(
            displayName = "School & College Security",
            tagline = "Crowd-scatter silence detection & unattended-bag tracking",
            emoji = "🏫",
            gemmaSystemRules = """
                You are the reasoning core of a campus safety unit.
                RULES:
                R1_SCATTER_SILENCE: Campus chatter is the expected baseline. If the
                  acoustic counterfactual reports the chatter baseline collapsing to
                  dead silence AND vision shows people moving rapidly outward
                  (scattering), treat as pre-violence signature → ELEVATED, request
                  probes if visibility is poor, escalate to VERIFIED_THREAT on any
                  corroborating cue.
                R2_ABANDONED_BAG: A scene-graph ABANDONED edge on a bag/backpack whose
                  owner left the area over 60s ago in a crowded zone → ELEVATED; over
                  180s or owner exited frame entirely → VERIFIED_THREAT.
                R3_PLAY_SUPPRESSION: Crowd + laughter/cheering fusion verdict is
                  recess or sport → BENIGN, do not re-evaluate for 60 seconds.
            """.trimIndent(),
            expectedAudioTokens = setOf("speech", "chatter", "children"),
            silenceToleranceSec = 4.0,
            tracksAbandonedObjects = true,
            wakeObjectLabels = setOf("person", "backpack", "bag", "knife"),
        )
    ),

    BABY_MONITOR(
        ModeRuleSet(
            displayName = "Intelligent Baby Monitor",
            tagline = "Silent-SIDS detection, IR airway probing, bleed-proof alarms",
            emoji = "👶",
            gemmaSystemRules = """
                You are the reasoning core of an infant safety monitor.
                RULES:
                R1_EXTERNAL_BLEED: Loud audio (barking, traffic, siren) while the
                  infant is visually asleep and undisturbed is external room bleed
                  → BENIGN, suppress. Never wake parents for the neighbor's dog.
                R2_AIRWAY_OBSTRUCTION: If a blanket/object overlaps the infant's face
                  region and breathing motion is not visually confirmable, DO NOT
                  GUESS: call activate_infrared_floodlight (or
                  camera2_set_exposure_compensation +2_EV_infrared_flash_pulse) and
                  re-evaluate sub-blanket chest motion on the next frame.
                R3_SILENT_SIDS: The breathing-cadence audio baseline is sacred. If the
                  counterfactual engine reports breathing tokens missing beyond
                  tolerance AND no visual chest motion after an IR probe →
                  VERIFIED_THREAT, immediately call trigger_local_vibration_alert
                  (local Bluetooth/Wi-Fi, zero cloud).
                R4_NORMAL_STIR: Brief cries with visible limb motion are normal sleep
                  cycling → BENIGN.
            """.trimIndent(),
            expectedAudioTokens = setOf("breathing", "infant", "baby"),
            silenceToleranceSec = 12.0,
            usesActiveProbing = true,
            localAlertActuator = true,
            wakeObjectLabels = setOf("infant", "person", "blanket"),
            sentinelFps = 1,
        )
    ),

    ROAD_SAFETY(
        ModeRuleSet(
            displayName = "Road Safety",
            tagline = "Wildlife detection, road hazard monitoring & paramedic dispatch",
            emoji = "🦌",
            gemmaSystemRules = """
                You are the reasoning core of a road safety monitoring node.
                RULES:
                R1_HAZARD_DETECTED: If a dead animal or severe road hazard is detected and there is no movement, output VERIFIED_THREAT and call paramedics/road-services.
            """.trimIndent(),
            expectedAudioTokens = setOf("traffic", "impact", "siren"),
            tracksAbandonedObjects = true,
            usesActiveProbing = true,
            wakeObjectLabels = setOf("person", "animal", "car", "truck"),
        )
    );
}

/** Everything the pipeline needs to know to run a mode — pure data. */
data class ModeRuleSet(
    val displayName: String,
    val tagline: String,
    val emoji: String,
    /** System prompt block injected ahead of every Gemma 3 reasoning tick. */
    val gemmaSystemRules: String,
    /** Ambient tokens whose ABSENCE the counterfactual engine treats as signal. */
    val expectedAudioTokens: Set<String>,
    val silenceToleranceSec: Double = 8.0,
    /** Nano-YOLO labels that qualify as wake triggers in Layer 1. */
    val wakeObjectLabels: Set<String> = setOf("person"),
    val sentinelFps: Int = 2,
    val gForceWakeThreshold: Float = Float.MAX_VALUE,   // disabled unless dashcam
    val usesDualCamera: Boolean = false,
    val usesGpsTelemetry: Boolean = false,
    val usesActiveProbing: Boolean = true,
    val tracksAbandonedObjects: Boolean = false,
    val emitsDispatchAlerts: Boolean = false,
    val localAlertActuator: Boolean = false,
)
