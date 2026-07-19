package de.heimai.app.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * Macht Heim-AI in Android unter
 * Einstellungen → Apps → Standard-Apps → Digitaler Assistent auswählbar.
 * Damit reagiert die App auch auf die System-Assistent-Geste
 * (Power-Button lang / Eck-Swipe) — zusätzlich zum eigenen Wake Word.
 */
class HeimVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    companion object {
        @Volatile
        var instance: HeimVoiceInteractionService? = null
            private set
    }
}

class HeimVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HeimVoiceSession(this)
}

/**
 * Die Session selbst delegiert sofort an die transluzente
 * AssistantOverlayActivity — robuster als Compose direkt im
 * Session-Window zu hosten, optisch identisch (Popup über der App).
 */
class HeimVoiceSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            runCatching { context.startActivity(intent) }
        }
        hide()
    }
}

/**
 * Stub: Android verlangt für die Assistant-Rolle einen deklarierten
 * RecognitionService. Die echte Spracherkennung läuft serverseitig
 * (Whisper im Orchestrator), daher meldet der Stub nur "nicht verfügbar".
 */
class HeimRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, callback: Callback?) {
        runCatching { callback?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(callback: Callback?) {}
    override fun onStopListening(callback: Callback?) {}
}
