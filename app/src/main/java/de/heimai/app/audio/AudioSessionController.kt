package de.heimai.app.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Schaltet das Gerät für den Realtime Talk in den Kommunikations-Audiomodus —
 * denselben Pfad, den Telefonate/VoIP nutzen. Nur so greift die geräteeigene,
 * anrufqualitäts-**Echo-Unterdrückung** (auf AOSP/vielen Geräten die WebRTC-AEC):
 * Sie kann das Lautsprechersignal nur dann aus dem Mikrofonsignal herausrechnen,
 * wenn Aufnahme (VOICE_COMMUNICATION) UND Wiedergabe (Voice-Call-Stream) über
 * diesen Modus laufen.
 *
 * Muss beim Verlassen des Talks wieder zurückgesetzt werden, sonst bleibt das
 * Gerät im Kommunikationsmodus (gedämpfte Medienlautstärke etc.).
 */
class AudioSessionController(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    @Synchronized
    fun enterCommunication() {
        if (active) return
        previousMode = audioManager.mode
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            routeToSpeaker()
        }
        active = true
    }

    @Synchronized
    fun exitCommunication() {
        if (!active) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousMode
        }
        active = false
    }

    /** Freisprechen über den Lautsprecher, aber durch den Kommunikationspfad (mit AEC). */
    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) audioManager.setCommunicationDevice(speaker)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }
}
