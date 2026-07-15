package com.aegisedge.os.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.aegisedge.os.core.fusion.AcousticCounterfactualEngine
import com.aegisedge.os.core.graph.VectorizedSceneGraph
import com.aegisedge.os.core.inference.AcousticScreener
import com.aegisedge.os.core.inference.GemmaReasonerEngine
import com.aegisedge.os.core.inference.ModelRegistry
import com.aegisedge.os.core.inference.NanoYoloDetector
import com.aegisedge.os.core.inference.PaliGemmaEngine
import com.aegisedge.os.core.inference.ShieldGemmaEngine
import com.aegisedge.os.core.memory.MemoryManager
import com.aegisedge.os.core.model.PrivacyVerdict
import com.aegisedge.os.core.model.ThreatLevel
import com.aegisedge.os.core.model.WakeSignal
import com.aegisedge.os.core.probing.ActiveProbingDispatcher
import com.aegisedge.os.core.sensors.AudioCaptureManager
import com.aegisedge.os.core.sensors.CameraController
import com.aegisedge.os.core.sensors.FrameConverter
import com.aegisedge.os.core.sensors.MotionTelemetry
import com.aegisedge.os.modes.OperationalMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The S.E.N.S.E. orchestrator: wires sensors → 4 layers → enclave as
 * structured-concurrency coroutine flows. Includes Demo Scenario Engine.
 */
class SensePipeline(
    private val context: Context,
    val mode: OperationalMode,
    private val videoUri: Uri? = null,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // ---- Shared infrastructure ------------------------------------------
    private val registry = ModelRegistry(context)
    val camera = CameraController(context)
    private val audio = AudioCaptureManager()
    private val telemetry = MotionTelemetry(context)
    val memory = MemoryManager(context)

    // ---- Layer 1 (always-on, lightweight) ---------------------------------
    private val yolo = NanoYoloDetector(context, registry)
    private val screener = AcousticScreener(context, registry)
    private val gate = Layer1PerceptionGate(yolo, screener, mode.ruleSet)

    // ---- Layers 2-4 (lazy — models load on first wake) --------------------
    private val paliGemma = PaliGemmaEngine(context, registry)
    private val reasoner = GemmaReasonerEngine(context, registry)
    private val shieldGemma = ShieldGemmaEngine(context, registry)
    private val sceneGraph = VectorizedSceneGraph()
    private val prober = ActiveProbingDispatcher(context, camera, audio)
    private val counterfactuals = AcousticCounterfactualEngine(
        mode.ruleSet.expectedAudioTokens, mode.ruleSet.silenceToleranceSec
    )
    private val layer2 = Layer2SemanticVision(paliGemma, screener, sceneGraph)
    private val layer3 = Layer3LogicEngine(reasoner, counterfactuals, prober, telemetry, mode.ruleSet)
    private val layer4 = Layer4PrivacyEnclave(
        shieldGemma, memory, sceneGraph, layer3, telemetry, mode
    )

    // ---- Observable status for the UI -------------------------------------
    data class PipelineStatus(
        val running: Boolean = false,
        val engaged: Boolean = false,               // heavy models awake?
        val lastWake: String = "—",
        val lastAssessment: String = "—",
        val lastEnclaveNote: String = "—",
        val bufferedSeconds: Int = 0,
        val incidents: Int = 0,
        val latestFrame: Bitmap? = null,
        val logs: List<String> = emptyList(),
    )

    private val _status = MutableStateFlow(PipelineStatus())
    val status: StateFlow<PipelineStatus> = _status

    private var lastRms = 0.0
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun log(message: String) {
        val timeStr = LocalTime.now().format(timeFormatter)
        val newLogs = (_status.value.logs + "[$timeStr] $message").takeLast(50)
        _status.value = _status.value.copy(logs = newLogs)
    }

    fun start() {
        _status.value = _status.value.copy(running = true)
        log("S.E.N.S.E. background sentinel service initialized.")
        log("Operational Mode: ${mode.ruleSet.displayName}")

        // Sentinel streams: feed the RAM buffer AND the perception gate.
        scope.launch {
            var frameCount = 0
            camera.frames(videoUri = videoUri).collect { frame ->
                memory.pushFrame(frame)
                gate.screenFrame(frame)
                val bitmap = FrameConverter.to224Bitmap(frame)
                frameCount++
                if (frameCount % 10 == 1) {
                    log("Sentinel frame #$frameCount processed. RAM buffer active.")
                }
                _status.value = _status.value.copy(
                    bufferedSeconds = memory.bufferedSeconds(),
                    latestFrame = bitmap
                )
            }
        }
        scope.launch {
            audio.chunks().collect { chunk ->
                memory.pushAudio(chunk)
                lastRms = screener.rmsEnergy(chunk)
                gate.screenAudio(chunk)
            }
        }
        if (mode.ruleSet.gForceWakeThreshold < Float.MAX_VALUE) {
            scope.launch {
                telemetry.gForceSpikes(mode.ruleSet.gForceWakeThreshold)
                    .collect { gate.screenGForce(it) }
            }
        }
        if (mode.ruleSet.usesDualCamera) {
            scope.launch { camera.engageDualCamera() }
        }

        // The wake → L2 → L3 → L4 chain.
        scope.launch {
            gate.wakes.collectLatest { wake -> onWake(wake) }
        }
    }

    private suspend fun onWake(wake: WakeSignal) {
        log("L1 Gate Woke: Triggered by ${wake.trigger} (${wake.label})")
        _status.value = _status.value.copy(
            engaged = true, lastWake = "${wake.trigger}: ${wake.label}"
        )
        camera.setRegime(CameraController.Regime.ENGAGED)   // full fps into the ring buffer
        try {
            val snapshot = layer2.analyze(wake.frame, wake.audio)
            log("L2 Semantic Extraction: Caption - \"${snapshot.caption}\"")
            log("L2 Scene Graph: ${snapshot.entities.size} entities grounded.")
            
            val assessment = layer3.evaluate(snapshot, lastRms)
            log("L3 Logic Engine: Evaluating ruleset. ThreatLevel - ${assessment.level}")
            log("L3 Logic Engine Rationale: ${assessment.rationale}")
            _status.value = _status.value.copy(
                lastAssessment = "${assessment.level}: ${assessment.rationale.take(140)}"
            )

            // Probe in flight → stay engaged, next frame re-enters the loop.
            if (assessment.probeRequest != null) {
                log("L3 Active Probing: Initiated hardware request for '${assessment.probeRequest.function}'")
                return
            }

            val outcome = layer4.arbitrate(assessment)
            log("L4 Privacy Enclave: Verdict - ${outcome.verdict}. Note: ${outcome.note}")
            _status.value = _status.value.copy(
                lastEnclaveNote = outcome.note,
                incidents = _status.value.incidents +
                    if (outcome.verdict == PrivacyVerdict.THREAT_FORENSIC_LOCK) 1 else 0,
            )

            if (assessment.level != ThreatLevel.ELEVATED.name) {
                standDown()
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            log("Pipeline error occurred: ${t.message}")
            _status.value = _status.value.copy(lastEnclaveNote = "pipeline error: ${t.message}")
            standDown()
        }
    }

    /** Heavy models back to sleep; sentinel regime resumes — the battery saver. */
    private suspend fun standDown() {
        log("Sentinel stand-down. GPU models returned to standby sleep.")
        camera.setRegime(CameraController.Regime.SENTINEL)
        paliGemma.sleep()
        reasoner.sleep()
        shieldGemma.sleep()
        _status.value = _status.value.copy(engaged = false)
    }

    fun triggerDemoScenario(scenarioName: String) {
        scope.launch {
            log("[DEMO] Triggering Hackathon Scenario: $scenarioName")
            _status.value = _status.value.copy(engaged = true)
            
            when (scenarioName) {
                "staged_fraud" -> {
                    log("[L1_GATE] Accelerometer registers horizontal impact spike: 2.8g.")
                    delay(1000)
                    log("[L2_VLM] Grounded entity: vehicle_A. Trajectory vectors show reversing motion: -2.4 m/s.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates rules: R2_CRASH_FOR_CASH triggered.")
                    log("[L3_LOGIC] Rationale: Impact spike following preceding reversing vehicle trajectory. Verdict: VERIFIED_THREAT.")
                    delay(1000)
                    log("[L4_ENCLAVE] ShieldGemma auditing rationale... Upheld (staged reversing fraud detected).")
                    log("[L4_ENCLAVE] Forensic Lock: writing evidence.mp4 (SHA-256: d8e3a...) and manifest.json.")
                    _status.value = _status.value.copy(
                        lastWake = "G_FORCE_SPIKE: crash_sensor",
                        lastAssessment = "VERIFIED_THREAT: Staged reversing crash-for-cash fraud detected.",
                        lastEnclaveNote = "Forensic Lock complete -> ${context.filesDir.absolutePath}/SENSE_Forensic_Enclave/SENSE-1721-FRAUD",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "driver_medical" -> {
                    log("[L1_GATE] Dual-camera Sentinel detects head droop. Waking Gemma...")
                    delay(1000)
                    log("[L2_VLM] Driver head droop detected. Acoustic screener logs sustained silence.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R4_DRIVER_MEDICAL. Ambiguity: need dual camera verification.")
                    log("[L3_LOGIC] Triggering active probe: switch_to_dual_camera")
                    delay(1500)
                    log("[SENSOR] Executed probe switch_to_dual_camera -> Front cabin camera engaged.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 secondary check. Verdict: VERIFIED_THREAT. Driver posture slumped, no response.")
                    log("[L3_LOGIC] Emergency alert dispatched to EMS with GPS: 40.7128, -74.0060")
                    delay(1000)
                    log("[L4_ENCLAVE] Forensic Lock complete. Cabin audio/video saved.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: driver_droop",
                        lastAssessment = "VERIFIED_THREAT: Driver medical blackout emergency verified.",
                        lastEnclaveNote = "Forensic Lock complete -> EMS alerted.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "pothole_mapping" -> {
                    log("[L1_GATE] Accel registers vertical-axis shock. Camera scanning oncoming asphalt.")
                    delay(1000)
                    log("[L2_VLM] Asphalt crevice anomaly detected near center grid.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates rules. R3_POTHOLE triggered. Rationale: Pothole obstacle. Logging GPS (40.7130, -74.0055).")
                    delay(1000)
                    log("[L4_ENCLAVE] Log written. Staging anonymous DOT pothole map payload. Upload scheduled on Wi-Fi connection.")
                    _status.value = _status.value.copy(
                        lastWake = "G_FORCE_SPIKE: vertical_shock",
                        lastAssessment = "BENIGN: Pothole road damage logged at (40.7130, -74.0055).",
                        lastEnclaveNote = "DOT Pothole payload staged.",
                        engaged = false
                    )
                }
                "assault_weapon" -> {
                    log("[L1_GATE] Audio screener detects high-decibel distress cry. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: person_A (weapon posture), knife_B. Bounding box coordinates recorded.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates rules. Cross-Modal corroboration: distress audio matches weapon vision. Verdict: VERIFIED_THREAT.")
                    delay(1000)
                    log("[L4_ENCLAVE] ShieldGemma redacting bystander PII... Upheld.")
                    log("[L4_ENCLAVE] Generating Dispatch Flash Alert JSON payload containing suspect descriptors and SHA-256 evidence seals.")
                    _status.value = _status.value.copy(
                        lastWake = "ACOUSTIC_ANOMALY: distress_cry",
                        lastAssessment = "VERIFIED_THREAT: Assault with weapon corroborated.",
                        lastEnclaveNote = "Dispatch alert sent (PII redacted).",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "street_performance" -> {
                    log("[L1_GATE] Crowd movement detected. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: 6 persons near grid center. Audio tags: music, rhythmic clapping, laughter.")
                    delay(1000)
                    log("[L3_LOGIC] Symbolic Fusion pre-pass runs. Rule CROWD_BUT_LAUGHTER triggered: crowd vision contradicts threat. Suppressing alert.")
                    delay(1000)
                    log("[L4_ENCLAVE] Benign verdict. Wiping RAM ring buffer and memory graphs. GC invoked.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: crowd_movement",
                        lastAssessment = "BENIGN: Benign public street performance suppressed.",
                        lastEnclaveNote = "RAM wiped. System.gc() complete.",
                        engaged = false
                    )
                }
                "incapacitated_posture" -> {
                    log("[L1_GATE] Lanes scan: motorcycle posture anomaly detected. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entity: person lying on ground near motorcycle. Velocity = 0.")
                    delay(1000)
                    log("[L3_LOGIC] Cross-Modal Fusion: PERSON_DOWN_SILENT matches. Verdict: VERIFIED_THREAT. Incident flagged.")
                    delay(1000)
                    log("[L4_ENCLAVE] Human operator reviews text alert: Incapacitated motorist, GPS 40.7145, -74.0040. Operator greenlights dispatch.")
                    _status.value = _status.value.copy(
                        lastWake = "OBJECT_OF_INTEREST: motorcycle_crash",
                        lastAssessment = "VERIFIED_THREAT: Incapacitated motorcyclist road hazard detected.",
                        lastEnclaveNote = "HITL Operator approved dispatch.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "threat_scatter" -> {
                    log("[L1_GATE] Baseline chatter audio drops below threshold. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Bounding boxes show rapid directional outward movement (scattering).")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R1_SCATTER_SILENCE. Rationale: Baseline chatter collapse + crowd scatter. Verdict: VERIFIED_THREAT.")
                    delay(1000)
                    log("[L4_ENCLAVE] Forensic Lock: saving footage of campus quad incident.")
                    _status.value = _status.value.copy(
                        lastWake = "ACOUSTIC_SILENCE: scatter_silence",
                        lastAssessment = "VERIFIED_THREAT: Active threat scatter silence signature detected.",
                        lastEnclaveNote = "Forensic Lock complete.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "unattended_bag" -> {
                    log("[L1_GATE] Motion verified near locker zone. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: person_A, backpack_B.")
                    log("[L2_VLM] Scene Graph: person_A [OWNS] backpack_B.")
                    delay(1000)
                    log("[L2_VLM] Time series check: person_A exited frame. Backpack_B remains.")
                    log("[L2_VLM] Scene Graph edge shifts: backpack_B [ABANDONED].")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R2_ABANDONED_BAG. Verdict: VERIFIED_THREAT.")
                    log("[L4_ENCLAVE] Log written. Backpack origin traces back to person_A at timestamp t0.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: backpack_left",
                        lastAssessment = "VERIFIED_THREAT: Unattended backpack loitering limit exceeded.",
                        lastEnclaveNote = "Backpack trace completed.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "baby_obstruction" -> {
                    log("[L1_GATE] Breathing cadence check: visual breathing motion obscured. Waking Gemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: infant, blanket overlap.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R2_AIRWAY_OBSTRUCTION. Ambiguity: blanket covers face.")
                    log("[L3_LOGIC] Triggering active probe: activate_infrared_floodlight")
                    delay(1500)
                    log("[SENSOR] Executed probe activate_infrared_floodlight -> IR exposure boosted.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 secondary check. Verdict: BENIGN. Airway cleared, infant breathing normal.")
                    log("[L4_ENCLAVE] Benign verdict. Wiping temporary logs. GC complete.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: airway_obscured",
                        lastAssessment = "BENIGN: Airway clear verified via low-light infrared pulse.",
                        lastEnclaveNote = "RAM wiped. System.gc() complete.",
                        engaged = false
                    )
                }
                "baby_sids" -> {
                    log("[L1_GATE] Acoustic counterfactual reports infant breathing cadence stopped for 12s.")
                    delay(1000)
                    log("[L2_VLM] Infant present in crib, no visual chest motion detected.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R3_SILENT_SIDS. Rationale: Silent SIDS cadence drop. Verdict: VERIFIED_THREAT.")
                    log("[L3_LOGIC] Triggering active probe: trigger_local_vibration_alert")
                    delay(1500)
                    log("[SENSOR] Firing local parent wearable alert over Wi-Fi/Bluetooth...")
                    _status.value = _status.value.copy(
                        lastWake = "ACOUSTIC_SILENCE: breathing_stopped",
                        lastAssessment = "VERIFIED_THREAT: Silent SIDS alert triggered - breathing stopped.",
                        lastEnclaveNote = "Local wearable vibration alert sent.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "baby_bleed" -> {
                    log("[L1_GATE] Audio screener detects high-decibel dog barking. Waking Gemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: infant sleeping peacefully. Closed eyes.")
                    delay(1000)
                    log("[L3_LOGIC] Cross-Modal Fusion: SLEEPING_BABY_EXTERNAL_BLEED matches. Noise is external room-bleed. Suppressing alarm.")
                    delay(1000)
                    log("[L4_ENCLAVE] Benign verdict. RAM buffer wiped.")
                    _status.value = _status.value.copy(
                        lastWake = "ACOUSTIC_ANOMALY: dog_barking",
                        lastAssessment = "BENIGN: External noise bleed suppressed. Infant sleeping.",
                        lastEnclaveNote = "RAM wiped. parents sleeping.",
                        engaged = false
                    )
                }
                "platform_edge" -> {
                    log("[L1_GATE] Platform edge motion detected. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entity: person_A loitering within platform edge region for 95s.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R3_PLATFORM_EDGE. Rationale: Platform edge limit exceeded. Focus beam mic.")
                    log("[L3_LOGIC] Triggering active probe: focus_directional_microphone_zone")
                    delay(1500)
                    log("[SENSOR] Mic beam focused on edge zone. Audio tags: heavy breathing, sobbing.")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 secondary check. Verdict: VERIFIED_THREAT. Platform passenger distress warning.")
                    log("[L4_ENCLAVE] Dispatch Alert: loitering passenger emergency. Alerting station manager.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: platform_edge_stay",
                        lastAssessment = "VERIFIED_THREAT: Passenger distress loitering at platform edge.",
                        lastEnclaveNote = "Station manager alerted.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
                "unattended_suitcase" -> {
                    log("[L1_GATE] Motion verified near platform bench. Waking PaliGemma...")
                    delay(1000)
                    log("[L2_VLM] Grounded entities: person_A, suitcase_B.")
                    log("[L2_VLM] Scene Graph: person_A [OWNS] suitcase_B.")
                    delay(1000)
                    log("[L2_VLM] Time series check: person_A boarded departing train. Suitcase_B remains.")
                    log("[L2_VLM] Scene Graph edge shifts: suitcase_B [ABANDONED].")
                    delay(1000)
                    log("[L3_LOGIC] Gemma 3 evaluates R2_UNATTENDED_BAGGAGE. Verdict: VERIFIED_THREAT.")
                    log("[L4_ENCLAVE] Forensic Lock complete. Baggage tracking details saved.")
                    _status.value = _status.value.copy(
                        lastWake = "MOTION: suitcase_left",
                        lastAssessment = "VERIFIED_THREAT: Unattended suitcase left on platform.",
                        lastEnclaveNote = "Incident recorded.",
                        incidents = _status.value.incidents + 1,
                        engaged = false
                    )
                }
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        camera.shutdown()
        yolo.close(); screener.close()
        paliGemma.close(); reasoner.close(); shieldGemma.close()
        memory.wipe()
        _status.value = PipelineStatus()
    }
}
