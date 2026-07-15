package com.aegisedge.os.core.inference

import android.content.Context
import java.io.File

/**
 * Single source of truth for where every model bundle lives.
 *
 * Load order per model:
 *   1. `/data/local/tmp/aegis_models/<name>`  — adb-pushed during development
 *   2. `filesDir/models/<name>`               — downloaded / side-loaded at runtime
 *   3. APK `assets/models/<name>`             — shipped in the release build
 *
 * Nothing else in the codebase knows a model path, so injecting new
 * .task / .tflite files later is a pure file-drop with no code changes.
 */
class ModelRegistry(private val context: Context) {

    object Names {
        const val NANO_YOLO = "nano_yolo_int4.tflite"
        const val AUDIO_CLASSIFIER = "audio_classifier_yamnet_int4.tflite"
        const val PALIGEMMA_3B = "paligemma_3b_mix_224_int4.task"
        const val GEMMA3_1B = "gemma3_1b_int4.task"
        const val GEMMA3N_FALLBACK = "gemma3n_e2b_int4.task"
        const val SHIELDGEMMA_2B = "shieldgemma_2b_int4.task"
    }

    private val devDir = File("/data/local/tmp/aegis_models")
    private val runtimeDir = File(context.filesDir, "models")

    /**
     * Resolves a model to an absolute filesystem path, staging it out of the
     * APK assets on first use (MediaPipe LLM API requires a real file path).
     */
    fun resolve(name: String): File {
        devDir.resolve(name).takeIf { it.exists() }?.let { return it }
        runtimeDir.resolve(name).takeIf { it.exists() }?.let { return it }
        return stageFromAssets(name)
    }

    /** Gemma 3 1B preferred; falls back to Gemma 3n if that's what was dropped in. */
    fun resolveReasoner(): File =
        runCatching { resolve(Names.GEMMA3_1B) }
            .getOrElse { resolve(Names.GEMMA3N_FALLBACK) }

    fun isAvailable(name: String): Boolean =
        devDir.resolve(name).exists() || runtimeDir.resolve(name).exists() ||
            runCatching { context.assets.open("models/$name").close(); true }.getOrDefault(false)

    private fun stageFromAssets(name: String): File {
        runtimeDir.mkdirs()
        val target = runtimeDir.resolve(name)
        context.assets.open("models/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }
}
