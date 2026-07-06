package de.heimai.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streaming-Wiedergabe der vom Server gepushten TTS-PCM-Chunks.
 * Default 24 kHz (XTTS-v2-Ausgabe); Sample-Rate kommt aus der ersten
 * audio_chunk-Nachricht des Servers.
 */
class AudioPlayer {

    private var track: AudioTrack? = null
    private var currentSampleRate = 0
    private var builtCommunication = false

    /**
     * true = Wiedergabe über den Voice-Call-Pfad (USAGE_VOICE_COMMUNICATION). Nötig,
     * damit die geräteeigene Echo-Unterdrückung im Realtime Talk dieses Signal als
     * Referenz kennt und aus dem Mikrofonsignal herausrechnet. Vor dem Abspielen setzen.
     */
    @Volatile
    var communication: Boolean = false

    @Synchronized
    fun play(pcm: ByteArray, sampleRate: Int) {
        if (track == null || currentSampleRate != sampleRate || builtCommunication != communication) {
            release()
            currentSampleRate = sampleRate
            builtCommunication = communication
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val attributes = if (communication) {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            } else {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            }
            track = AudioTrack(
                attributes,
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuffer, sampleRate), // ~0,5 s Puffer
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).also { it.play() }
        }
        track?.write(pcm, 0, pcm.size)
    }

    @Synchronized
    fun stop() {
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    @Synchronized
    fun release() {
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        currentSampleRate = 0
    }
}
