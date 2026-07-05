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
 * (<1 % CPU auf modernen Geräten, kein Netzwerk, komplett lokal).
 *
 * Der AccessKey ist Picovoices Lizenz-/Attestierungsmechanismus (die Engine
 * startet ohne gültigen Key nicht) — er wird zentral vom Orchestrator
 * bereitgestellt (GET /v1/config) und kann in den AI-Einstellungen optional
 * überschrieben werden.
 *
 * @param keyword           Built-in-Keyword-Name ODER [CUSTOM]
 * @param customKeywordPath Pfad zu einer eigenen .ppn-Datei (leer = Built-in)
 * @param customModelPath   Pfad zu einem Sprachmodell .pv (nötig für nicht-englische
 *                          eigene Wake Words; leer = englisches Standardmodell)
 */
class PorcupineEngine(
    context: Context,
    accessKey: String,
    keyword: String,
    customKeywordPath: String = "",
    customModelPath: String = "",
    onDetected: () -> Unit,
) : WakeWordEngine {

    private val manager: PorcupineManager

    init {
        val builder = PorcupineManager.Builder()
            .setAccessKey(accessKey)
            .setSensitivity(0.65f)
        if (customKeywordPath.isNotBlank()) {
            builder.setKeywordPath(customKeywordPath)
            if (customModelPath.isNotBlank()) builder.setModelPath(customModelPath)
        } else {
            val builtIn = runCatching { Porcupine.BuiltInKeyword.valueOf(keyword) }
                .getOrDefault(Porcupine.BuiltInKeyword.COMPUTER)
            builder.setKeyword(builtIn)
        }
        manager = builder.build(context.applicationContext) { _ -> onDetected() }
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

        /** Sentinel für "eigene .ppn-Datei verwenden". */
        const val CUSTOM = "CUSTOM"
    }
}
