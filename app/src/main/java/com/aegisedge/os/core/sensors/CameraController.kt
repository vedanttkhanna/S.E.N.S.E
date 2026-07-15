package com.aegisedge.os.core.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import com.aegisedge.os.core.model.VideoFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2-based sentinel camera with two operating regimes:
 *
 *  - SENTINEL: 1-2 fps low-power stream feeding only the Layer-1 gate.
 *  - ENGAGED:  full frame-rate stream feeding the RAM ring buffer + Layer 2,
 *              optionally with a concurrent second camera (Dual-Camera API)
 *              for cabin/driver monitoring in Dashcam mode.
 *
 * It is also the physical actuator surface for Active Probing: exposure
 * compensation, ISO, torch-as-IR-floodlight pulses, and dual-camera switching
 * are all requested by Gemma 3 through [com.aegisedge.os.core.probing.ActiveProbingDispatcher].
 */
class CameraController(private val context: Context) {

    enum class Regime { SENTINEL, ENGAGED }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("aegis-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var secondaryDevice: CameraDevice? = null   // dual-camera (cabin) stream

    val regime = MutableStateFlow(Regime.SENTINEL)

    /** Back camera id, resolved once. */
    val primaryCameraId: String by lazy {
        cameraManager.cameraIdList.first { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /** Front camera id for driver-monitoring dual streams; null on single-camera devices. */
    val cabinCameraId: String? by lazy {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    /**
     * Cold flow of YUV frames. Collecting starts the camera; cancelling stops it.
     * Frame rate is pinned by [Regime]: SENTINEL requests a [1..2] fps AE target
     * so the ISP itself idles — this is the "wake-word for video" power trick.
     */
    @SuppressLint("MissingPermission")
    fun frames(width: Int = 640, height: Int = 480, videoUri: Uri? = null): Flow<VideoFrame> = callbackFlow {
        if (videoUri != null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 10000L
                val startTimeReal = System.currentTimeMillis()
                while (true) {
                    val timeMs = System.currentTimeMillis() - startTimeReal
                    if (timeMs >= durationMs) break
                    
                    val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                        val pixels = IntArray(width * height)
                        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        val grayscaleBytes = ByteArray(width * height)
                        for (j in pixels.indices) {
                            val pixel = pixels[j]
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            grayscaleBytes[j] = ((r + g + b) / 3).toByte()
                        }
                        val frame = VideoFrame(
                            timestampNanos = timeMs * 1_000_000,
                            data = grayscaleBytes,
                            width = width,
                            height = height,
                            cameraId = "video-upload"
                        )
                        trySend(frame)
                    }
                    kotlinx.coroutines.delay(10)
                }
            } catch (e: Exception) {
                // handle error
            } finally {
                runCatching { retriever.release() }
            }
            close()
        } else {
            val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    trySend(img.toVideoFrame(primaryCameraId))
                }
            }, cameraHandler)

            val cam = openCamera(primaryCameraId)
            device = cam
            val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                applyRegime(this, regime.value)
            }
            session = createSession(cam, listOf(reader.surface)).also {
                it.setRepeatingRequest(request.build(), null, cameraHandler)
            }
        }

        awaitClose { shutdown() }
    }

    /** Re-issues the repeating request when the pipeline flips regimes. */
    fun setRegime(newRegime: Regime) {
        regime.value = newRegime
        reissueRepeatingRequest()
    }

    // -------------------------------------------------------------------
    // Active Probing actuators (invoked ONLY via ActiveProbingDispatcher)
    // -------------------------------------------------------------------

    /** camera2_set_exposure_compensation(+2_EV_infrared_flash_pulse) etc. */
    fun setExposureCompensation(evSteps: Int) =
        mutateRequest { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps) }

    /** Metro mode: brute-force sensitivity for dim subway platforms. */
    fun setManualIso(iso: Int) = mutateRequest {
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
    }

    /**
     * "IR floodlight": on hardware without a discrete IR emitter we pulse the
     * torch at minimum brightness — enough near-IR leakage for the sensor to
     * pick up breathing motion under a blanket without waking a sleeping infant.
     */
    fun pulseInfraredFloodlight(durationMs: Long = 350) {
        runCatching {
            cameraManager.setTorchMode(primaryCameraId, true)
            cameraHandler.postDelayed(
                { runCatching { cameraManager.setTorchMode(primaryCameraId, false) } },
                durationMs
            )
        }
    }

    /** Dashcam mode: bring the cabin (front) camera up concurrently. */
    @SuppressLint("MissingPermission")
    suspend fun engageDualCamera(): Boolean {
        val cabinId = cabinCameraId ?: return false
        if (secondaryDevice != null) return true
        secondaryDevice = openCamera(cabinId)
        // TODO(model-injection): wire the cabin stream into a second frames() flow
        // consumed by the driver-monitoring ruleset (head droop detection).
        return true
    }

    fun releaseDualCamera() { secondaryDevice?.close(); secondaryDevice = null }

    // -------------------------------------------------------------------

    private fun applyRegime(builder: CaptureRequest.Builder, r: Regime) {
        // Removed hardcoded CONTROL_AE_TARGET_FPS_RANGE which causes IllegalArgumentException 
        // on many devices that don't support Range(1, 2).
        builder.set(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO
        )
    }

    private fun mutateRequest(block: CaptureRequest.Builder.() -> Unit) {
        val cam = device ?: return
        val reader = imageReader ?: return
        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            applyRegime(this, regime.value)
            block()
        }
        session?.setRepeatingRequest(builder.build(), null, cameraHandler)
    }

    private fun reissueRepeatingRequest() = mutateRequest { }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) = cont.resume(cam)
                override fun onDisconnected(cam: CameraDevice) = cam.close()
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("Camera $id error $error")
                    )
                }
            }, cameraHandler)
        }

    @Suppress("DEPRECATION") // SessionConfiguration path is API 28+; minSdk is 26
    private suspend fun createSession(
        cam: CameraDevice,
        surfaces: List<android.view.Surface>,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) = cont.resume(s)
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("Capture session configuration failed")
                )
            }
        }, cameraHandler)
    }

    fun shutdown() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { secondaryDevice?.close() }
        runCatching { imageReader?.close() }
        session = null; device = null; secondaryDevice = null; imageReader = null
    }

    private inline fun <R> Image.use(block: (Image) -> R): R =
        try { block(this) } finally { close() }

    private fun Image.toVideoFrame(cameraId: String): VideoFrame {
        // Pack Y, U, V planes contiguously; FrameConverter handles the rest.
        val y = planes[0].buffer; val u = planes[1].buffer; val v = planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val bytes = ByteArray(ySize + uSize + vSize)
        y.get(bytes, 0, ySize)
        u.get(bytes, ySize, uSize)
        v.get(bytes, ySize + uSize, vSize)
        return VideoFrame(timestamp, bytes, width, height, cameraId)
    }
}

/** Placeholder converters — production impl should use libyuv or RenderScript-replacement. */
object FrameConverter {
    /** YUV → ARGB Bitmap scaled to exactly 224x224 for PaliGemma. */
    fun to224Bitmap(frame: VideoFrame): Bitmap {
        // TODO(perf): replace with libyuv NV21→ARGB + hardware scaler.
        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(frame.width * frame.height)
        for (i in pixels.indices) {
            val yV = frame.data[i].toInt() and 0xFF   // luminance-only placeholder
            pixels[i] = (0xFF shl 24) or (yV shl 16) or (yV shl 8) or yV
        }
        bmp.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        return Bitmap.createScaledBitmap(bmp, 224, 224, true)
    }

    /** YUV → MPImage for the Layer-1 Nano-YOLO detector. */
    fun toMpImage(frame: VideoFrame): com.google.mediapipe.framework.image.MPImage =
        com.google.mediapipe.framework.image.BitmapImageBuilder(to224Bitmap(frame)).build()
}
