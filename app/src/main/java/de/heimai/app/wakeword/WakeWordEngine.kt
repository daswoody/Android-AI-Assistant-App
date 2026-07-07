package de.heimai.app.wakeword

/**
 * Abstraktion über die Wake-Word-Engine. Aktuelle Implementierung:
 * [OpenWakeWordEngine] (openWakeWord, frei, ohne Lizenz/AccessKey).
 * Historisch stand hier Picovoice Porcupine — wegen fehlender Lizenz ersetzt.
 * Die Abstraktion bleibt, damit später z. B. microWakeWord (ESP32-Parität)
 * nachgerüstet werden kann.
 */
interface WakeWordEngine {
    fun start()
    fun stop()
    fun release()
}
