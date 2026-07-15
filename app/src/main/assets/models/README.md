# Model Drop-In Directory

Aegis-Edge loads all models from this directory (or from
`/data/local/tmp/aegis_models/` during development — see `ModelRegistry.kt`).
Drop the INT4-quantized bundles here with these exact filenames:

| File | Model | Layer | Runtime |
|---|---|---|---|
| `nano_yolo_int4.tflite` | MediaPipe Object Detector (Nano-YOLO class) | L1 Perception Gate | LiteRT CPU/NNAPI |
| `audio_classifier_yamnet_int4.tflite` | MediaPipe Audio Classifier | L1 Perception Gate | LiteRT CPU |
| `paligemma_3b_mix_224_int4.task` | PaliGemma 3B (224x224 vision) | L2 Semantic Vision | MediaPipe LLM / mobile GPU |
| `gemma3_1b_int4.task` | Gemma 3 1B (or `gemma3n_e2b_int4.task`) | L3 Logic Engine | MediaPipe LLM / mobile GPU |
| `shieldgemma_2b_int4.task` | ShieldGemma 2B | L4 Privacy Redaction | MediaPipe LLM / mobile GPU |

Nothing else in the codebase hard-codes model internals: swapping a file here
is all that is required to upgrade a layer.
