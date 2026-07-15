package com.aegisedge.os.core.memory

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import com.aegisedge.os.core.model.AudioChunk
import com.aegisedge.os.core.model.ForensicManifest
import com.aegisedge.os.core.model.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * Dual-state memory manager — the heart of Aegis-Edge's privacy model.
 *
 * STATE 1 (default): a 30-second rolling ring buffer of video frames and PCM
 * audio living purely in volatile RAM. Old data is overwritten continuously;
 * nothing ever touches flash storage.
 *
 * STATE 2 (Forensic Lock): the moment Layer 3 flags a verified anomaly AND
 * Layer 4 upholds it, [forensicLock] atomically dumps the buffer + live event
 * to /storage/emulated/0/Aegis_Forensic_Enclave/ as .mp4/.wav, computes
 * SHA-256 hashes, writes the Gemma-generated JSON manifest, and appends an
 * entry to the append-only cryptographic ledger (each entry chained to the
 * previous entry's hash, blockchain-style).
 *
 * If the event is benign, [wipe] drops every reference and calls System.gc()
 * so the evidence never existed anywhere recoverable.
 */
class MemoryManager(
    private val context: Context,
    private val windowSeconds: Int = 30,
    private val nominalFps: Int = 24,
    enclaveOverride: File? = null,
) {
    private val enclaveDir: File = enclaveOverride
        ?: File(context.filesDir, "SENSE_Forensic_Enclave")

    private val maxFrames = windowSeconds * nominalFps
    private val maxAudioChunks = windowSeconds * 2   // 500 ms chunks

    private val frameRing = ArrayDeque<VideoFrame>(maxFrames)
    private val audioRing = ArrayDeque<AudioChunk>(maxAudioChunks)
    private val lock = Any()

    private val json = Json { prettyPrint = true }

    // ------------------------------------------------------------------
    // STATE 1 — rolling RAM buffer
    // ------------------------------------------------------------------

    fun pushFrame(frame: VideoFrame) = synchronized(lock) {
        if (frameRing.size >= maxFrames) frameRing.pollFirst()   // overwrite oldest
        frameRing.addLast(frame)
    }

    fun pushAudio(chunk: AudioChunk) = synchronized(lock) {
        if (audioRing.size >= maxAudioChunks) audioRing.pollFirst()
        audioRing.addLast(chunk)
    }

    fun bufferedSeconds(): Int = synchronized(lock) { frameRing.size / nominalFps }

    /** BENIGN path: obliterate the window and aggressively return pages to the OS. */
    fun wipe() {
        synchronized(lock) {
            frameRing.clear()
            audioRing.clear()
        }
        System.gc()
    }

    // ------------------------------------------------------------------
    // STATE 2 — Forensic Lock
    // ------------------------------------------------------------------

    data class LockResult(
        val incidentDir: File,
        val videoFile: File,
        val videoSha256: String,
        val audioFile: File,
        val audioSha256: String,
        val manifestFile: File,
    )

    /**
     * Dumps the RAM window to the enclave. Called ONLY by Layer 4 after a
     * ShieldGemma-upheld VERIFIED_THREAT. [buildManifest] receives the two
     * hashes and returns the Gemma-3-authored manifest to persist beside them.
     */
    suspend fun forensicLock(
        buildManifest: suspend (videoSha256: String, audioSha256: String,
                                videoFile: String, audioFile: String) -> ForensicManifest,
    ): LockResult = withContext(Dispatchers.IO) {
        val (frames, chunks) = synchronized(lock) {
            frameRing.toList() to audioRing.toList()   // snapshot; live capture keeps appending
        }
        val incidentId = "AEGIS-${Instant.now().epochSecond}-${UUID.randomUUID().toString().take(8)}"
        val dir = File(enclaveDir, incidentId).apply { mkdirs() }

        val videoFile = File(dir, "evidence.mp4")
        val audioFile = File(dir, "evidence.wav")
        encodeMp4(frames, videoFile)
        writeWav(chunks, audioFile)

        val videoHash = sha256(videoFile)
        val audioHash = sha256(audioFile)

        val manifest = buildManifest(videoHash, audioHash, videoFile.name, audioFile.name)
        val manifestFile = File(dir, "manifest.json").apply {
            writeText(json.encodeToString(manifest))
        }

        ForensicLedger(enclaveDir).append(incidentId, videoHash, audioHash, sha256(manifestFile))
        LockResult(dir, videoFile, videoHash, audioFile, audioHash, manifestFile)
    }

    // ------------------------------------------------------------------
    // Encoding + hashing
    // ------------------------------------------------------------------

    /** SHA-256 via Android's hardware-accelerated provider (AndroidOpenSSL → ARMv8 crypto ext). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Encodes buffered YUV frames to H.264 inside an .mp4 container via
     * MediaCodec + MediaMuxer (hardware encoder on both Snapdragon & MediaTek).
     * Boilerplate-level implementation — the color-format negotiation loop is
     * left as a TODO seam for device bring-up.
     */
    private fun encodeMp4(frames: List<VideoFrame>, out: File) {
        if (frames.isEmpty()) { out.writeBytes(ByteArray(0)); return }
        val w = frames.first().width
        val h = frames.first().height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, nominalFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val frameUs = 1_000_000L / nominalFps

            frames.forEachIndexed { i, frame ->
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    codec.getInputBuffer(inIdx)!!.apply { clear(); put(frame.data) }
                    codec.queueInputBuffer(inIdx, 0, frame.data.size, i * frameUs, 0)
                }
                drainEncoder(codec, muxer, info, endOfStream = false,
                    onFormat = { track = muxer.addTrack(it); muxer.start(); muxerStarted = true },
                    track = { track })
            }
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, frames.size * frameUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(codec, muxer, info, endOfStream = true,
                onFormat = { track = muxer.addTrack(it); muxer.start(); muxerStarted = true },
                track = { track })
        } finally {
            runCatching { codec.stop(); codec.release() }
            runCatching { if (muxerStarted) muxer.stop(); muxer.release() }
        }
    }

    private inline fun drainEncoder(
        codec: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo,
        endOfStream: Boolean, onFormat: (MediaFormat) -> Unit, track: () -> Int,
    ) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && track() >= 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) muxer.writeSampleData(track(), buf, info)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Standard 16-bit PCM WAV writer. */
    private fun writeWav(chunks: List<AudioChunk>, out: File) {
        val sampleRate = chunks.firstOrNull()?.sampleRate ?: 16_000
        val totalSamples = chunks.sumOf { it.pcm.size }
        val dataBytes = totalSamples * 2
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
            header.putInt(16); header.putShort(1); header.putShort(1)
            header.putInt(sampleRate); header.putInt(sampleRate * 2)
            header.putShort(2); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(dataBytes)
            raf.write(header.array())
            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            chunks.forEach { c -> c.pcm.forEach { body.putShort(it) } }
            raf.write(body.array())
        }
    }
}
