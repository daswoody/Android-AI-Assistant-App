package de.heimai.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Nimmt Mikrofon-Audio als PCM16 mono 16 kHz auf und liefert ~100-ms-Chunks.
 * 16 kHz passt direkt zum Whisper-STT des Orchestrators (Phase 1.8).
 */
class AudioStreamer(
    private val sampleRate: Int = 16_000,
) {
    /**
     * @param source Aufnahmequelle. Push-to-Talk nutzt VOICE_RECOGNITION (roher, für
     *   STT optimiert). Der Realtime-Talk mit gleichzeitiger Wiedergabe nutzt
     *   VOICE_COMMUNICATION, weil dieser Pfad die Plattform-Echo-Unterdrückung (AEC)
     *   aktiviert — sonst nimmt das Mikro im Freisprechbetrieb die eigene Antwort auf.
     *   Zusätzlich werden, falls verfügbar, AcousticEchoCanceler + NoiseSuppressor auf
     *   die AudioRecord-Session gelegt.
     *
     * RECORD_AUDIO muss vor dem Collect bereits erteilt sein.
     */
    @SuppressLint("MissingPermission")
    fun stream(source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val chunkBytes = sampleRate / 10 * 2 // 100 ms PCM16
        val record = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord konnte nicht initialisiert werden")
        }
        val aec = if (AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        else null
        val ns = if (NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        else null
        try {
            record.startRecording()
            val buffer = ByteArray(chunkBytes)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
