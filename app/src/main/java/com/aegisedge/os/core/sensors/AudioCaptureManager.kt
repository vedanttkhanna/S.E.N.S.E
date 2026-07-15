package com.aegisedge.os.core.sensors

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aegisedge.os.core.model.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Continuous 16 kHz mono PCM capture feeding both the Layer-1 acoustic
 * screener and the 30-second RAM ring buffer.
 *
 * Also the actuator for the "focus_directional_microphone_zone" probe: on
 * multi-mic hardware this switches AudioSource / enables beamforming hints;
 * the placeholder here just records the requested zone.
 */
class AudioCaptureManager {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MILLIS = 500L
    }

    @Volatile var focusedZone: String? = null
        private set

    /** Cold flow of half-second PCM chunks; cancelling the collector stops capture. */
    @SuppressLint("MissingPermission")
    fun chunks(): Flow<AudioChunk> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val chunkSamples = (SAMPLE_RATE * CHUNK_MILLIS / 1000).toInt()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,   // raw signal — no OS noise suppression,
            SAMPLE_RATE,                              // counterfactuals need true silence levels
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkSamples * 2)
        )
        recorder.startRecording()
        try {
            val buf = ShortArray(chunkSamples)
            while (true) {
                var filled = 0
                while (filled < buf.size) {
                    val n = recorder.read(buf, filled, buf.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                emit(AudioChunk(System.nanoTime(), buf.copyOf(filled)))
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Active-probe actuator. Zones are mode-defined labels ("crib", "cabin",
     * "platform_edge"). Real beamforming requires vendor mic-array APIs;
     * this seam is where they plug in.
     */
    fun focusDirectionalZone(zone: String) {
        focusedZone = zone
        // TODO(hardware): apply MicrophoneDirection.setPreferredMicrophoneDirection()
        // on devices that support it (API 29+), else vendor beamforming extension.
    }
}
