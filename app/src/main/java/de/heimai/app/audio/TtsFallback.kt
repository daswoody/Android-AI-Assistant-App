package de.heimai.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-Device-TTS-Fallback über die Android-TextToSpeech-Engine.
 * Wird genutzt, wenn der Orchestrator keine Audio-Antwort liefert
 * (Server-TTS ausgefallen, kein Audio-Kanal, etc.).
 * Qualität ist geringer als XTTS-v2, aber die Antwort bleibt hörbar.
 */
class TtsFallback(context: Context) {

    private val ready = AtomicBoolean(false)
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.set(true)
    }

    init {
        tts.language = Locale.GERMAN
    }

    /** @return false, wenn die Engine (noch) nicht bereit ist. */
    fun speak(text: String): Boolean {
        if (!ready.get() || text.isBlank()) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "heimai-fallback")
        return true
    }

    fun stop() {
        if (ready.get()) tts.stop()
    }
}
