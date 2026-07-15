# S.E.N.S.E. — Universal Public Safety & Forensics Operating System

Edge-native, **neuro-symbolic** safety OS that runs entirely on a ~$150 Android
phone (Snapdragon / MediaTek). Gemma-family models execute locally via
**LiteRT / Google AI Edge** — no cloud, no data exfiltration, forensic-grade
evidence with a hash-chained local ledger.

## Project structure

```
S.E.N.S.E./
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts                  # LiteRT, MediaPipe (vision/audio/genai), Compose
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml           # camera+mic FGS, storage, BLE, sensors
│       ├── assets/models/README.md       # ← drop .task / .tflite bundles here
│       └── java/com/aegisedge/os/
│           ├── AegisApplication.kt       # boot-time model availability probe
│           ├── MainActivity.kt           # Compose host + permissions
│           ├── core/
│           │   ├── model/Events.kt       # all cross-layer data contracts
│           │   ├── inference/
│           │   │   ├── ModelRegistry.kt        # single source of model paths
│           │   │   ├── PerceptionModels.kt     # Nano-YOLO + audio classifier (L1)
│           │   │   ├── PaliGemmaEngine.kt      # 3B VLM, <loc> token parser (L2)
│           │   │   ├── GemmaReasonerEngine.kt  # Gemma 3 1B/3n, JSON contract (L3)
│           │   │   └── ShieldGemmaEngine.kt    # 2B audit + redaction (L4)
│           │   ├── sensors/
│           │   │   ├── CameraController.kt     # Camera2, dual-cam, probe actuators
│           │   │   ├── AudioCaptureManager.kt  # 16 kHz PCM, mic-zone focus
│           │   │   └── MotionTelemetry.kt      # accel/gyro/mag/GPS, G-force spikes
│           │   ├── memory/
│           │   │   ├── MemoryManager.kt        # 30 s RAM ring buffer + Forensic Lock
│           │   │   └── ForensicLedger.kt       # hash-chained append-only ledger
│           │   ├── graph/VectorizedSceneGraph.kt  # RAM 3D graph DB, OWNS/ABANDONED
│           │   ├── fusion/
│           │   │   ├── AcousticCounterfactualEngine.kt  # anomaly-by-absence
│           │   │   └── CrossModalFusion.kt              # vision-vs-audio veto rules
│           │   ├── probing/ActiveProbing.kt    # Gemma JSON function-call → hardware
│           │   └── pipeline/
│           │       ├── Layer1PerceptionGate.kt  # ultra-low-power wake-word gate
│           │       ├── Layer2SemanticVision.kt  # 224×224 → PaliGemma → scene graph
│           │       ├── Layer3LogicEngine.kt     # neuro-symbolic reasoning + probes
│           │       ├── Layer4PrivacyEnclave.kt  # ShieldGemma → wipe | Forensic Lock
│           │       ├── AegisPipeline.kt         # coroutine-flow orchestrator
│           │       └── SentinelForegroundService.kt
│           ├── modes/OperationalMode.kt   # the 5 modes as pure Gemma rulesets
│           └── ui/                        # Compose: mode selector + live monitor
└── app/src/test/…                        # JVM tests (ledger chain integrity)
```

## The 4-layer autonomous pipeline

| Layer | Models | Power state | Job |
|---|---|---|---|
| **1 · Perception Gate** | Nano-YOLO + MediaPipe Audio Classifier (INT4) | Always-on, 1–2 fps | Wake-word screening; LLMs stay unloaded |
| **2 · Semantic Vision** | PaliGemma 3B `.task` on GPU | On wake | 224×224 frame → `<loc>` boxes + caption; audio tags; Vectorized Scene Graph in RAM |
| **3 · Logic Engine** | Gemma 3 1B / 3n (INT4) | On wake | Mode rules × fusion × counterfactuals → threat verdict or Active Probe function call; maintains JSON "State of the World" ledger |
| **4 · Privacy Enclave** | ShieldGemma 2B (INT4) | On verdict | Benign → RAM wipe + `System.gc()`. Verified threat → Forensic Lock: `.mp4`/`.wav` dump, SHA-256, manifest, chained ledger entry |

## Storage & privacy model

- **Default:** 30-second rolling ring buffer in volatile RAM. Nothing touches flash.
- **Forensic Lock:** `/storage/emulated/0/Aegis_Forensic_Enclave/<incident>/`
  containing `evidence.mp4`, `evidence.wav`, `manifest.json`, plus an entry in
  `ledger.chain` where each line commits to the previous line's SHA-256 —
  tamper-evident without any cloud notary (`ForensicLedger.verifyChain()`).

## Injecting models

Drop INT4 bundles into `app/src/main/assets/models/` (or push to
`/data/local/tmp/aegis_models/` during development). Filenames are listed in
`assets/models/README.md`; `ModelRegistry.kt` is the only file that knows them.

## Build

```bash
./gradlew :app:assembleDebug
```
