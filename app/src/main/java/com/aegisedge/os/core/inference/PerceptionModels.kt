package com.aegisedge.os.core.inference

import android.content.Context
import com.aegisedge.os.core.model.AudioChunk
import com.aegisedge.os.core.model.VideoFrame
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Layer-1 lightweight detectors. These are the ONLY models allowed to run
 * while the system is in its low-power sentinel state.
 */

data class Detection(val label: String, val confidence: Float,
                     val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float)

data class AudioTag(val label: String, val confidence: Float)

/** MediaPipe Object Detector wrapping the INT4 Nano-YOLO bundle. */
class NanoYoloDetector(context: Context, registry: ModelRegistry) : AutoCloseable {

    private val detector: ObjectDetector? = if (registry.isAvailable(ModelRegistry.Names.NANO_YOLO)) {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(registry.resolve(ModelRegistry.Names.NANO_YOLO).absolutePath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)   // gate polls at ~1 fps; no stream mode needed
                .setScoreThreshold(0.45f)
                .setMaxResults(8)
                .build()
        )
    } else null

    /** [image] must be converted from the Camera2 YUV frame by the caller (see FrameConverter). */
    fun detect(image: MPImage): List<Detection> {
        val det = detector
        if (det != null) {
            return det.detect(image).toDetections()
        }
        // Mock fallback for demo: trigger a threat target every 15 seconds
        if ((System.currentTimeMillis() / 1000) % 15 == 0L) {
            val detections = mutableListOf(Detection("person", 0.92f, 100f, 100f, 400f, 400f))
            val rng = kotlin.random.Random
            if (rng.nextFloat() < 0.35f) {
                detections.add(Detection("backpack", 0.78f, 320f, 280f, 420f, 400f))
            }
            if (rng.nextFloat() < 0.20f) {
                detections.add(Detection("bag", 0.71f, 50f, 300f, 150f, 400f))
            }
            if (rng.nextFloat() < 0.15f) {
                detections.add(Detection("bicycle", 0.67f, 450f, 150f, 600f, 380f))
            }
            if (rng.nextFloat() < 0.10f) {
                detections.add(Detection("car", 0.83f, 0f, 50f, 300f, 250f))
            }
            return detections
        }
        return emptyList()
    }

    private fun ObjectDetectorResult.toDetections(): List<Detection> =
        detections().map { d ->
            val cat = d.categories().first()
            val b = d.boundingBox()
            Detection(cat.categoryName(), cat.score(), b.left, b.top, b.right, b.bottom)
        }

    override fun close() {
        detector?.close()
    }
}

/** MediaPipe Audio Classifier (YAMNet-class, INT4) for acoustic anomaly screening. */
class AcousticScreener(context: Context, registry: ModelRegistry) : AutoCloseable {

    private val classifier: AudioClassifier? = if (registry.isAvailable(ModelRegistry.Names.AUDIO_CLASSIFIER)) {
        AudioClassifier.createFromOptions(
            context,
            AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(registry.resolve(ModelRegistry.Names.AUDIO_CLASSIFIER).absolutePath)
                        .build()
                )
                .setScoreThreshold(0.30f)
                .setMaxResults(5)
                .build()
        )
    } else null

    fun classify(chunk: AudioChunk): List<AudioTag> {
        val cls = classifier
        if (cls != null) {
            val floats = FloatArray(chunk.pcm.size) { chunk.pcm[it] / 32768f }
            val audioData = AudioData.create(
                AudioData.AudioDataFormat.builder()
                    .setNumOfChannels(1)
                    .setSampleRate(chunk.sampleRate.toFloat())
                    .build(),
                floats.size
            ).apply { load(floats) }
            return cls.classify(audioData).toTags()
        }
        // Mock fallback for demo: if RMS energy is high (> 0.04), trigger a mock acoustic anomaly
        val rms = rmsEnergy(chunk)
        if (rms > 0.04) {
            return listOf(AudioTag("scream", 0.88f))
        }
        return emptyList()
    }

    /** RMS energy — used by the counterfactual engine to detect true dead silence. */
    fun rmsEnergy(chunk: AudioChunk): Double {
        var acc = 0.0
        for (s in chunk.pcm) acc += s.toDouble() * s
        return kotlin.math.sqrt(acc / chunk.pcm.size) / 32768.0
    }

    private fun AudioClassifierResult.toTags(): List<AudioTag> =
        classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()
            ?.categories()?.map { AudioTag(it.categoryName(), it.score()) }
            ?: emptyList()

    override fun close() {
        classifier?.close()
    }
}
