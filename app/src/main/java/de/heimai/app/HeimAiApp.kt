package de.heimai.app

import android.app.Application
import de.heimai.app.core.AppContainer
import de.heimai.app.core.CrashLog

class HeimAiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Abstürze aufzeichnen, BEVOR irgendetwas anderes initialisiert wird —
        // der Stacktrace ist danach in den Einstellungen unter "Diagnose" abrufbar.
        CrashLog.install(this)
        container = AppContainer(this)
    }

    companion object {
        fun from(application: Application): HeimAiApp = application as HeimAiApp
    }
}
