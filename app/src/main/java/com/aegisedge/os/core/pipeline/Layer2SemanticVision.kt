package com.aegisedge.os.core.pipeline

import com.aegisedge.os.core.graph.VectorizedSceneGraph
import com.aegisedge.os.core.inference.AcousticScreener
import com.aegisedge.os.core.inference.PaliGemmaEngine
import com.aegisedge.os.core.model.AudioChunk
import com.aegisedge.os.core.model.SceneSnapshot
import com.aegisedge.os.core.model.VideoFrame
import com.aegisedge.os.core.sensors.FrameConverter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * LAYER 2 — Semantic Vision & Scene Graphing.
 *
 * Only runs after Layer 1 fires. The wake frame is compressed to 224x224 and
 * handed to PaliGemma 3B on the GPU; its <locXXXX> grounding tokens are parsed
 * into boxes, fed through the RAM-resident [VectorizedSceneGraph] for stable
 * track ids and relation edges, and merged with concurrently-generated
 * semantic audio tags into one [SceneSnapshot] for the reasoner.
 */
class Layer2SemanticVision(
    private val paliGemma: PaliGemmaEngine,
    private val screener: AcousticScreener,
    val sceneGraph: VectorizedSceneGraph,
) {

    suspend fun analyze(frame: VideoFrame?, audio: AudioChunk?): SceneSnapshot = coroutineScope {
        val now = frame?.timestampNanos ?: audio?.timestampNanos ?: System.nanoTime()

        // Vision and audio tagging run CONCURRENTLY — they share no state.
        val visionJob = frame?.let {
            async {
                paliGemma.awake()
                paliGemma.describeScene(FrameConverter.to224Bitmap(it))
            }
        }
        val audioJob = audio?.let { async { screener.classify(it).map { t -> t.label } } }

        val vision = visionJob?.await()
        val audioTags = audioJob?.await() ?: emptyList()

        val entities = vision?.let {
            sceneGraph.ingest(now, it.groundedEntities)
        } ?: emptyList()
        sceneGraph.evictStale(now)

        SceneSnapshot(
            timestampNanos = now,
            entities = entities,
            caption = vision?.caption ?: "",
            audioTags = audioTags,
            relations = sceneGraph.relations(),
        )
    }
}
