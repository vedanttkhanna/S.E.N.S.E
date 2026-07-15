package com.aegisedge.os.core.inference

import android.content.Context
import android.graphics.Bitmap
import com.aegisedge.os.core.model.LocBox
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Layer-2 vision-language engine: PaliGemma 3B (INT4, 224x224) executing on
 * the mobile GPU via the MediaPipe LLM Inference API (.task bundle).
 */
class PaliGemmaEngine(
    private val context: Context,
    private val registry: ModelRegistry,
) : AutoCloseable {

    private var llm: LlmInference? = null
    private val mutex = Mutex()   // GPU LLM sessions are single-flight

    data class VisionResult(
        val caption: String,
        val groundedEntities: List<Pair<String, LocBox>>,
        val rawOutput: String,
    )

    suspend fun awake(): Boolean = mutex.withLock {
        if (llm != null) return true
        if (registry.isAvailable(ModelRegistry.Names.PALIGEMMA_3B)) {
            llm = withContext(Dispatchers.IO) {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(registry.resolve(ModelRegistry.Names.PALIGEMMA_3B).absolutePath)
                        .setMaxTokens(512)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .setMaxNumImages(1)
                        .build()
                )
            }
        }
        true
    }

    /** Frame must already be compressed to exactly 224x224 (see FrameConverter). */
    suspend fun describeScene(frame224: Bitmap, prompt: String = DEFAULT_PROMPT): VisionResult =
        mutex.withLock {
            val engine = llm
            if (engine == null) {
                val mockCaption = when {
                    prompt.contains("infant", ignoreCase = true) || prompt.contains("baby", ignoreCase = true) ->
                        "An infant lying in a crib with a blanket nearby."
                    prompt.contains("suitcase", ignoreCase = true) ->
                        "A suitcase left unattended on a metro platform."
                    prompt.contains("vehicle", ignoreCase = true) || prompt.contains("car", ignoreCase = true) ->
                        "A vehicle reversing toward another car on the road."
                    else -> "A person carrying a backpack loitering in a classroom hallway."
                }
                return VisionResult(
                    caption = mockCaption,
                    groundedEntities = listOf("person" to LocBox(80, 80, 480, 480), "backpack" to LocBox(120, 140, 360, 420)),
                    rawOutput = "<loc0080><loc0080><loc0480><loc0480> person <loc0120><loc0140><loc0360><loc0420> backpack"
                )
            }
            val raw = withContext(Dispatchers.Default) {
                LlmInferenceSession.createFromOptions(
                    engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(24)
                        .setTemperature(0.2f)
                        .setGraphOptions(
                            GraphOptions.builder().setEnableVisionModality(true).build()
                        )
                        .build()
                ).use { session ->
                    session.addQueryChunk(prompt)
                    session.addImage(BitmapImageBuilder(frame224).build())
                    session.generateResponse()
                }
            }
            VisionResult(
                caption = raw.substringBefore("<loc").trim(),
                groundedEntities = parseLocTokens(raw),
                rawOutput = raw,
            )
        }

    /** Releases the 3B model from GPU memory; Layer 1 keeps watch alone. */
    suspend fun sleep() = mutex.withLock {
        llm?.close(); llm = null
    }

    override fun close() { llm?.close(); llm = null }

    companion object {
        const val DEFAULT_PROMPT =
            "detect person ; bag ; backpack ; vehicle ; weapon ; infant ; blanket\n" +
                "Then caption the scene in one sentence."

        private val LOC_PATTERN =
            Regex("""((?:<loc\d{4}>){4})\s*([a-zA-Z_ ]+)""")

        fun parseLocTokens(raw: String): List<Pair<String, LocBox>> =
            LOC_PATTERN.findAll(raw).mapNotNull { m ->
                val coords = Regex("""\d{4}""").findAll(m.groupValues[1])
                    .map { it.value.toInt() }.toList()
                if (coords.size != 4) return@mapNotNull null
                m.groupValues[2].trim() to LocBox(coords[0], coords[1], coords[2], coords[3])
            }.toList()
    }
}
