# S.E.N.S.E.  Spatial & Environmental Neuro-Symbolic Engine

> **Perceive. Reason. Protect.**

S.E.N.S.E. is an edge-native, neuro-symbolic environmental safety system designed to run locally on consumer Android hardware and dedicated municipal edge nodes.

Instead of continuously streaming surveillance data to the cloud, S.E.N.S.E. combines lightweight perception, multimodal reasoning, temporal scene memory, deterministic safety rules, and incident-triggered evidence preservation directly on the edge.

The system is built around a simple principle:

> **MediaPipe detects change. The scene graph remembers change. Gemma 3n understands change.**

Raw camera and microphone streams remain local. Heavy multimodal inference is activated only when a lightweight perception gate detects a meaningful environmental change.

---

## Why S.E.N.S.E.?

Traditional automated surveillance systems often make decisions from isolated visual detections:

```text
Person detected
        ↓
Rapid movement detected
        ↓
Possible threat
        ↓
Alert
```

This approach lacks environmental context and can create large numbers of false alerts.

S.E.N.S.E. evaluates an event across multiple sources of evidence:

```text
Visual observations
        +
Acoustic context
        +
Temporal scene history
        +
Spatial relationships
        +
Device telemetry
        +
Deterministic safety rules
        ↓
Gemma 3n multimodal reasoning
        ↓
BENIGN | UNCERTAIN | POTENTIAL THREAT
```

The goal is not to detect more events.

The goal is to **understand an event before escalating it**.

---

# Architecture

S.E.N.S.E. follows a four-layer conditional-compute architecture.

```text
CAMERA + MICROPHONE + DEVICE SENSORS
                    │
                    ▼
        ┌─────────────────────────┐
        │  LAYER 1                │
        │  PERCEPTION GATE        │
        │                         │
        │  Lightweight vision     │
        │  Lightweight audio      │
        │  Sensor thresholds      │
        └────────────┬────────────┘
                     │
             Meaningful change?
                │           │
               NO          YES
                │           │
          Discard / idle     ▼
                     ┌─────────────────────────┐
                     │  LAYER 2                │
                     │  CONTEXT ACQUISITION    │
                     │                         │
                     │  Sampled frames         │
                     │  Audio event window     │
                     │  Sensor telemetry       │
                     │  Temporal scene state   │
                     └────────────┬────────────┘
                                  │
                                  ▼
                     ┌─────────────────────────┐
                     │  LAYER 3                │
                     │  NEURO-SYMBOLIC ENGINE │
                     │                         │
                     │  Gemma 3n E2B           │
                     │  Multimodal reasoning   │
                     │  Rule validation        │
                     │  Cross-modal fusion     │
                     └────────────┬────────────┘
                                  │
                     ┌────────────┼────────────┐
                     │            │            │
                  BENIGN      UNCERTAIN     POTENTIAL
                     │            │            THREAT
                     │            │              │
                  DISCARD    ACTIVE PROBE        ▼
                                  │      ┌─────────────────────┐
                                  │      │  LAYER 4            │
                                  └─────►│  INCIDENT ENCLAVE   │
                                         │                     │
                                         │  Evidence preserve  │
                                         │  SHA-256 integrity  │
                                         │  Incident manifest  │
                                         │  Human escalation   │
                                         └─────────────────────┘
```

The architecture uses **conditional compute**: model complexity scales with environmental ambiguity.

Large multimodal models are not continuously invoked on every camera frame.

---

# Project Structure

```text
S.E.N.S.E./
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
│
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   │
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       ├── assets/
│       │   └── models/
│       │       └── README.md
│       │
│       └── java/com/sense/edge/
│           │
│           ├── SenseApplication.kt
│           ├── MainActivity.kt
│           │
│           ├── core/
│           │   │
│           │   ├── model/
│           │   │   └── Events.kt
│           │   │
│           │   ├── inference/
│           │   │   ├── ModelRegistry.kt
│           │   │   ├── PerceptionModels.kt
│           │   │   └── GemmaReasonerEngine.kt
│           │   │
│           │   ├── sensors/
│           │   │   ├── CameraController.kt
│           │   │   ├── AudioCaptureManager.kt
│           │   │   └── MotionTelemetry.kt
│           │   │
│           │   ├── memory/
│           │   │   ├── MemoryManager.kt
│           │   │   └── ForensicLedger.kt
│           │   │
│           │   ├── graph/
│           │   │   └── TemporalSceneGraph.kt
│           │   │
│           │   ├── fusion/
│           │   │   ├── AcousticCounterfactualEngine.kt
│           │   │   └── CrossModalFusion.kt
│           │   │
│           │   ├── rules/
│           │   │   └── SafetyRuleEngine.kt
│           │   │
│           │   ├── probing/
│           │   │   └── ActiveProbing.kt
│           │   │
│           │   └── pipeline/
│           │       ├── Layer1PerceptionGate.kt
│           │       ├── Layer2ContextAcquisition.kt
│           │       ├── Layer3LogicEngine.kt
│           │       ├── Layer4IncidentEnclave.kt
│           │       ├── SensePipeline.kt
│           │       └── SentinelForegroundService.kt
│           │
│           ├── modes/
│           │   └── OperationalMode.kt
│           │
│           └── ui/
│               ├── screens/
│               ├── components/
│               └── theme/
│
└── app/src/test/
    ├── ForensicLedgerTest.kt
    ├── SafetyRuleEngineTest.kt
    └── SceneGraphTest.kt
```

---

# The Four-Layer Autonomous Pipeline

| Layer | Core components | Power state | Responsibility |
|---|---|---|---|
| **1 · Perception Gate** | MediaPipe + lightweight vision/audio models | Always active at low sampling rate | Detect meaningful environmental change |
| **2 · Context Acquisition** | Camera2, audio capture, sensor telemetry, temporal scene graph | Event-triggered | Assemble a short multimodal event window |
| **3 · Neuro-Symbolic Engine** | Gemma 3n E2B + deterministic rule engine | Event-triggered | Cross-modal reasoning, contradiction analysis, verdict generation and active probing |
| **4 · Incident Enclave** | Local evidence store + SHA-256 hash-chain ledger | Verified incident only | Preserve relevant evidence and generate an auditable incident manifest |

---

## Layer 1 — Perception Gate

The first layer acts as a **wake-word system for the physical environment**.

Lightweight visual and acoustic models continuously evaluate low-cost environmental signals such as:

- motion delta
- person or object count changes
- sudden acoustic energy changes
- basic sound events
- accelerometer or gyroscope spikes
- sustained object immobility

Example perception state:

```json
{
  "motion_delta": 0.84,
  "person_count_delta": -11,
  "audio_energy_delta": 0.76,
  "sensor_trigger": false
}
```

Layer 1 does **not** attempt to understand why an event occurred.

It answers one question:

> **Has the environment changed enough to justify deeper reasoning?**

If no meaningful change is detected, transient frames remain within the rolling memory window and are discarded as the buffer advances.

If an event threshold is crossed, Layer 2 is activated.

---

## Layer 2 — Multimodal Context Acquisition

Once the perception gate is triggered, S.E.N.S.E. assembles a bounded event context.

The context may contain:

- sampled visual frames
- a short PCM audio window
- accelerometer and gyroscope telemetry
- GPS metadata when explicitly enabled by the active mode
- lightweight tracked entity states
- recent temporal scene relationships
- acoustic baseline information

Example event context:

```json
{
  "event_id": "evt_7f92",
  "timestamp_ms": 1784102671221,
  "scene": {
    "previous_person_count": 18,
    "current_person_count": 5,
    "motion_pattern": "RADIAL_DISPERSION"
  },
  "audio": {
    "energy_delta": 0.81,
    "baseline_deviation": 0.92
  },
  "sensors": {
    "g_force_peak": 1.04
  }
}
```

Raw multimodal evidence is combined with an externalized temporal scene representation before reasoning.

---

# Temporal Scene Graph

S.E.N.S.E. maintains a lightweight relational representation of the local environment in volatile memory.

Rather than expecting a foundation model to continuously remember raw video, entity interactions are represented as temporal relationships.

Example:

```text
T+00s    PERSON_A  ── NEAR ───────────► OBJECT_B
T+05s    PERSON_A  ── INTERACTS_WITH ─► OBJECT_B
T+18s    PERSON_A  ── MOVING_AWAY ────► OBJECT_B
T+30s    OBJECT_B  ── STATE ──────────► UNATTENDED
```

A simplified state representation may look like:

```json
{
  "entity": "OBJECT_B",
  "history": [
    {
      "timestamp": 0,
      "relation": "NEAR",
      "target": "PERSON_A"
    },
    {
      "timestamp": 5,
      "relation": "INTERACTED_BY",
      "target": "PERSON_A"
    },
    {
      "timestamp": 30,
      "relation": "STATE",
      "target": "UNATTENDED"
    }
  ]
}
```

This **externalizes spatial and temporal memory from Gemma**.

Gemma receives the relevant scene state rather than an unbounded stream of historical video.

---

## Layer 3 — Gemma 3n Neuro-Symbolic Reasoning

**Gemma 3n E2B** acts as the multimodal cognitive core of S.E.N.S.E.

The model evaluates:

1. visual evidence
2. acoustic evidence
3. temporal scene history
4. device telemetry
5. deterministic rule matches
6. contradictory evidence

S.E.N.S.E. uses structured prompt engineering and schema-constrained JSON outputs rather than application-specific fine-tuning in the current prototype.

Conceptual reasoning contract:

```json
{
  "visual_hypothesis": "RAPID_CROWD_DISPERSION",
  "acoustic_hypothesis": "VOCAL_DISTRESS",
  "modal_relation": "SUPPORTIVE",
  "matched_rules": [
    "RAPID_DISPERSION",
    "DISTRESS_ACOUSTIC_EVENT"
  ],
  "uncertainty": 0.14,
  "decision": "POTENTIAL_THREAT",
  "recommended_action": "ESCALATE_TO_HUMAN"
}
```

The model must return one of three primary states:

```text
BENIGN
UNCERTAIN
POTENTIAL_THREAT
```

Gemma does not replace deterministic safety rules.

Instead:

> **Gemma interrogates symbolic hypotheses against multimodal reality.**

This combination of neural multimodal perception and explicit symbolic constraints forms the neuro-symbolic reasoning architecture.

---

# Cross-Modal Fusion

S.E.N.S.E. evaluates whether different sensor modalities **support or contradict each other**.

Example:

```text
VISION
Crowd moving rapidly
Raised hands
Dense movement

AUDIO
Laughter
Music
Applause
```

The visual evidence may initially resemble physical conflict.

However, the acoustic context contradicts that hypothesis.

```text
VISUAL HYPOTHESIS: POSSIBLE CONFLICT
AUDIO EVIDENCE: CONTRADICTORY
FINAL STATE: BENIGN / LOW CONFIDENCE THREAT
```

Alternatively:

```text
VISION
Crowd rapidly scattering

AUDIO
Sharp transient
Vocal distress

SCENE STATE
Crowd density decreased 72% in 4 seconds
```

The modalities support the same threat hypothesis.

S.E.N.S.E. is designed to search for evidence **against** a threat hypothesis as well as evidence supporting it.

---

# Acoustic Counterfactual Engine

Traditional acoustic event detection asks:

> **What sound occurred?**

S.E.N.S.E. additionally asks:

> **What sound should have occurred, but did not?**

The system maintains a lightweight acoustic baseline for the active environment.

Example:

```text
EXPECTED BASELINE
─────────────────
Traffic hum
Periodic horns
Pedestrian speech
Footsteps

CURRENT STATE
─────────────
Near-total silence
Rapid crowd disappearance
```

The absence of expected acoustic activity becomes a contextual anomaly.

This allows S.E.N.S.E. to reason about **environmental absence**, not only high-energy sound events.

---

# Active Probing

When the reasoning engine does not have sufficient evidence, it can request an additional sensor action.

Example Gemma output:

```json
{
  "decision": "UNCERTAIN",
  "uncertainty": 0.71,
  "probe": {
    "action": "CAMERA_EXPOSURE_COMPENSATION",
    "value": 2
  }
}
```

`ActiveProbing.kt` validates the requested action against a strict allowlist before interacting with device hardware.

```text
OBSERVE
   │
   ▼
UNCERTAIN
   │
   ▼
REQUEST PROBE
   │
   ▼
VALIDATE ACTION
   │
   ▼
ADJUST SENSOR
   │
   ▼
CAPTURE NEW EVIDENCE
   │
   ▼
REASON AGAIN
```

The model never receives unrestricted hardware access.

All probe actions are validated and bounded by the application.

---

## Layer 4 — Incident Enclave

S.E.N.S.E. maintains a rolling local event buffer.

Under normal operation:

```text
Capture
   ↓
Volatile rolling buffer
   ↓
Buffer advances
   ↓
Old context discarded
```

When Layer 3 verifies a potential incident, the relevant event window is preserved locally.

An incident directory contains:

```text
SENSE_Incident_Enclave/
└── <incident_id>/
    ├── evidence.mp4
    ├── evidence.wav
    └── manifest.json
```

Example manifest:

```json
{
  "incident_id": "incident_7f92",
  "timestamp_ms": 1784102671221,
  "decision": "POTENTIAL_THREAT",
  "observations": [
    "RAPID_CROWD_DISPERSION",
    "VOCAL_DISTRESS"
  ],
  "matched_rules": [
    "RULE_CROWD_DISPERSION_01"
  ],
  "human_review_required": true
}
```

The evidence files and manifest are hashed using SHA-256.

Any post-sealing modification to a hashed artifact results in a different digest and can therefore be detected during integrity verification.

---

# Hash-Chained Forensic Ledger

S.E.N.S.E. maintains an append-only, hash-chained local incident ledger.

Conceptually:

```text
ENTRY 001
HASH(previous = GENESIS + incident data)
       │
       ▼
ENTRY 002
HASH(previous = ENTRY_001_HASH + incident data)
       │
       ▼
ENTRY 003
HASH(previous = ENTRY_002_HASH + incident data)
```

Each ledger entry commits to the hash of the previous entry.

Example:

```json
{
  "sequence": 42,
  "incident_id": "incident_7f92",
  "evidence_sha256": "...",
  "manifest_sha256": "...",
  "previous_entry_hash": "...",
  "entry_hash": "..."
}
```

`ForensicLedger.verifyChain()` recalculates each entry and verifies the previous-entry references.

The ledger is designed to provide **local tamper evidence and integrity verification**.

It does not, by itself, establish legal admissibility or prove the semantic truth of recorded evidence.

---

# Storage and Privacy Model

### Default operation

- Raw camera and audio data remain local.
- Recent event context is maintained in a bounded rolling buffer.
- Old context is discarded as the buffer advances.
- No continuous cloud video or audio synchronization is required.
- Gemma inference executes locally.

### Incident state

When an event reaches the configured preservation threshold:

1. the relevant rolling event window is preserved;
2. evidence artifacts are written to local storage;
3. SHA-256 digests are calculated;
4. a structured incident manifest is generated;
5. a hash-chained ledger entry is appended;
6. the incident is surfaced for human review.

### External transmission

Network transmission is mode-dependent and opt-in.

Where enabled, S.E.N.S.E. is designed to transmit only parameterized event telemetry such as:

```json
{
  "event": "ROAD_HAZARD",
  "latitude": 28.XXXX,
  "longitude": 77.XXXX,
  "severity": "HIGH"
}
```

Raw continuous surveillance streams are not required for the core architecture.

---

# Human-in-the-Loop Safety

S.E.N.S.E. does not autonomously authorize law-enforcement dispatch or other high-impact interventions.

The intended escalation flow is:

```text
Environmental event
        ↓
Perception gate
        ↓
Gemma 3n reasoning
        ↓
Potential threat
        ↓
Incident manifest
        ↓
Human operator
        ↓
Evidence review
        ↓
Human-authorized escalation
```

> **AI reduces the operator's search space. It does not replace the operator's authority.**

---

# Operational Modes

The same reasoning architecture can be configured through mode-specific sensors, thresholds, and deterministic rules.

```text
MUNICIPAL_SENTINEL
DASHCAM
ROAD_HAZARD
CAMPUS_SENTINEL
TRANSIT_SENTINEL
BABY_MONITOR
```

Example:

```kotlin
enum class OperationalMode {
    MUNICIPAL_SENTINEL,
    DASHCAM,
    ROAD_HAZARD,
    CAMPUS_SENTINEL,
    TRANSIT_SENTINEL,
    BABY_MONITOR
}
```

Each mode defines:

- enabled sensors
- Layer 1 trigger thresholds
- temporal scene rules
- acoustic baseline strategy
- allowed active probes
- evidence preservation policy
- escalation policy

The underlying Gemma reasoning engine remains shared.

---

# Model and Runtime Strategy

| Responsibility | Component |
|---|---|
| Low-cost visual perception | MediaPipe / lightweight detector |
| Low-cost acoustic gating | MediaPipe audio classifier |
| Temporal and relational memory | In-memory scene graph |
| Symbolic reasoning | Native deterministic rule engine |
| Multimodal contextual reasoning | **Gemma 3n E2B** |
| Higher-capability edge tier | Gemma 3n E4B |
| Gemma edge inference | LiteRT-LM / Google AI Edge stack |
| Camera control | Android Camera2 API |
| Device telemetry | Android Sensor APIs |
| Integrity verification | SHA-256 + hash-chained ledger |
| UI | Jetpack Compose |

The smartphone prototype targets **Gemma 3n E2B** because tracking, temporal state management, and deterministic rule evaluation are externalized from the foundation model.

Gemma is therefore used for constrained multimodal contextual reasoning rather than continuous raw-stream processing.

---

# Why No RAG?

S.E.N.S.E. does not currently use Retrieval-Augmented Generation.

The system reasons primarily over:

- live multimodal observations
- recent temporal scene state
- device telemetry
- deterministic safety constraints
- mode-specific policy configuration

It does not require retrieval from a large external document corpus.

The temporal scene graph acts as a bounded **state-of-the-world ledger**, not a document knowledge base.

For this reason, RAG would introduce additional complexity without addressing the core reasoning problem.

---

# Injecting Models

Model bundles are stored under:

```text
app/src/main/assets/models/
```

During development, supported model artifacts may alternatively be provisioned through a development-only device path.

`ModelRegistry.kt` acts as the single source of truth for model identifiers and local paths.

```text
assets/models/
├── README.md
├── gemma-3n-e2b/
└── perception/
```

Model files are not committed to the repository unless their license and distribution terms explicitly permit redistribution.

---

# Build

```bash
./gradlew :app:assembleDebug
```

The generated debug APK is available under:

```text
app/build/outputs/apk/debug/
```

---

# Current Prototype Scope

S.E.N.S.E. is an experimental edge-AI research prototype.

The current architecture explores:

- conditional multimodal inference
- edge-native Gemma reasoning
- cross-modal contradiction analysis
- temporal relational scene memory
- acoustic counterfactual detection
- bounded hardware active probing
- local incident preservation
- hash-chained integrity verification

The system is **not a replacement for emergency services, certified forensic tooling, medical monitoring equipment, or autonomous law-enforcement decision systems**.

All high-impact incident classifications are intended to remain subject to human review.

---

## S.E.N.S.E.

### **Perceive. Reason. Protect.**

An edge-native experiment in giving safety systems something traditional surveillance lacks:

**context.**
