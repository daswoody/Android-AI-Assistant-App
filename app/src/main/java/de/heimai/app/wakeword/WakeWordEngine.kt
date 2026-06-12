package de.heimai.app.wakeword

import android.content.Context
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager

/**
 * Abstraktion über die Wake-Word-Engine, damit Porcupine später gegen
 * openWakeWord/microWakeWord (ESP32-Parität) austauschbar bleibt.
 */
interface WakeWordEngine {
    fun start()
    fun stop()
    fun release()
}

/**
 * Picovoice Porcupine: auf Effizienz optimierte On-Device-Erkennung
 * (<1 % CPU auf modernen Geräten, kein Netzwerk). Kostenlos für
 * Personal Use; benötigt einen AccessKey von console.picovoice.ai,
 * der in den AI-Einstellungen hinterlegt wird.
 */
class PorcupineEngine(
    context: Context,
    accessKey: String,
    keyword: String,
    onDetected: () -> Unit,
) : WakeWordEngine {

    private val manager: PorcupineManager

    init {
        val builtIn = runCatching { Porcupine.BuiltInKeyword.valueOf(keyword) }
            .getOrDefault(Porcupine.BuiltInKeyword.COMPUTER)
        manager = PorcupineManager.Builder()
            .setAccessKey(accessKey)
            .setKeyword(builtIn)
            .setSensitivity(0.65f)
            .build(context.applicationContext) { _ -> onDetected() }
    }

    override fun start() {
        manager.start()
    }

    override fun stop() {
        runCatching { manager.stop() }
    }

    override fun release() {
        stop()
        runCatching { manager.delete() }
    }

    companion object {
        /** In den Einstellungen wählbare Built-in-Keywords. */
        val KEYWORDS = listOf("COMPUTER", "JARVIS", "PORCUPINE", "BUMBLEBEE", "TERMINATOR")
    }
}
