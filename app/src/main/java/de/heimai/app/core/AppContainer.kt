package de.heimai.app.core

import android.content.Context
import androidx.room.Room
import de.heimai.app.audio.AudioSessionController
import de.heimai.app.audio.TtsFallback
import de.heimai.app.cards.CardLayoutRepository
import de.heimai.app.core.db.AppDatabase
import de.heimai.app.core.network.ApiClient
import de.heimai.app.core.settings.SettingsRepository
import de.heimai.app.tools.DeviceToolExecutor
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Einfacher, manueller DI-Container. Bewusst kein Hilt/Koin:
 * weniger Build-Magie, leichter nachvollziehbar (Lernprojekt).
 */
class AppContainer(private val context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: kein Read-Timeout
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "heimai.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val api: ApiClient by lazy { ApiClient(okHttp, json, settings) }

    val cardLayouts: CardLayoutRepository by lazy {
        CardLayoutRepository(context, api, database.cardLayoutDao(), json, settings)
    }

    val toolExecutor: DeviceToolExecutor by lazy { DeviceToolExecutor(context, settings) }

    val tts: TtsFallback by lazy { TtsFallback(context) }

    val audioSession: AudioSessionController by lazy { AudioSessionController(context) }
}
