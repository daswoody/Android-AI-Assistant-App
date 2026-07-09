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
    /** Geschriebene PCM16-Frames seit Track-Aufbau (für isDraining) */
    private var framesWritten = 0L

    /**
     * true = Wiedergabe über den Voice-Call-Pfad (USAGE_VOICE_COMMUNICATION). Nötig,
     * damit die geräteeigene Echo-Unterdrückung im Realtime Talk dieses Signal als
     * Referenz kennt und aus dem Mikrofonsignal herausrechnet. Vor dem Abspielen setzen.
     */
    @Volatile
    var communication: Boolean = false

    @Synchronized
    fun play(pcm: ByteArray, sampleRate: Int) {
        runCatching {
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
            framesWritten += pcm.size / 2
        }.onFailure {
            // Audio-HAL-Fehler dürfen die App nicht abstürzen lassen — Track verwerfen,
            // beim nächsten Chunk wird neu aufgebaut.
            android.util.Log.w("AudioPlayer", "Wiedergabe-Fehler: ${it.message}")
            release()
        }
    }

    /**
     * true, solange der Track noch gepufferte Samples abspielt. Wichtig für den
     * Echo-Schutz: Das `audio_end` des Servers kommt oft ~0,5–1 s bevor der
     * lokale Puffer wirklich leer ist — genau in diesem Fenster hört das Mikro
     * sonst den Ausklang der eigenen Antwort.
     */
    @Synchronized
    fun isDraining(): Boolean {
        val t = track ?: return false
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return framesWritten > head
    }

    /** Barge-in: sofort verstummen. Track verwerfen (Neuaufbau beim nächsten Chunk) —
     *  das setzt auch die Drain-Zähler eindeutig zurück. */
    @Synchronized
    fun stop() {
        release()
    }

    @Synchronized
    fun release() {
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        currentSampleRate = 0
        framesWritten = 0L
    }
}
