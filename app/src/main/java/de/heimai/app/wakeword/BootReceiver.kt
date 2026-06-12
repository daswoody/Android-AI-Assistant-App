package de.heimai.app.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.heimai.app.HeimAiApp

/**
 * Startet die Wake-Word-Erkennung nach dem Booten neu.
 * Hinweis: Ab Android 15 dürfen Microphone-FGS nicht mehr direkt aus
 * BOOT_COMPLETED starten — dort schlägt der Start leise fehl und der
 * Nutzer muss die App einmal öffnen (bekannte Plattform-Einschränkung).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? HeimAiApp ?: return
        val settings = app.container.settings.currentBlocking()
        if (settings.wakeWordEnabled && settings.picovoiceAccessKey.isNotBlank()) {
            WakeWordService.start(context)
        }
    }
}
