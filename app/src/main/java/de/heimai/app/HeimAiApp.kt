package de.heimai.app

import android.app.Application
import de.heimai.app.core.AppContainer

class HeimAiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun from(application: Application): HeimAiApp = application as HeimAiApp
    }
}
