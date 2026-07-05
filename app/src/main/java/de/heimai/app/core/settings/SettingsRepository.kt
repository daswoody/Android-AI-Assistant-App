package de.heimai.app.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val serverUrl: String = "",
    val authToken: String = "",
    val userName: String = "",
    val userTier: Int = 1,
    val themeName: String = "Indigo",
    val darkMode: String = "system", // system | dark | light
    val voiceId: String = "",
    val wakeWordEnabled: Boolean = false,
    /** Built-in-Keyword-Name oder "CUSTOM" für eine importierte .ppn-Datei */
    val wakeWordKeyword: String = "COMPUTER",
    val picovoiceAccessKey: String = "",
    /** Vom Orchestrator über GET /v1/config bezogener Key (zentrale Verwaltung) */
    val serverWakeWordKey: String = "",
    /** Pfad zur importierten eigenen Wake-Word-Datei (.ppn) */
    val customWakeWordPath: String = "",
    /** Pfad zum optionalen Sprachmodell (.pv, nötig für z. B. deutsche Wake Words) */
    val customWakeWordModelPath: String = "",
    /** Realtime Talk: Stille-Dauer in ms, nach der automatisch gesendet wird */
    val talkSilenceMs: Int = 900,
    /**
     * Rechte-Modus: true = bei entsperrtem Gerät keine separate Bestätigung
     * für sensible Tool-Aktionen; false = immer bestätigen.
     */
    val relaxedSecurity: Boolean = false,
    val ttsFallbackEnabled: Boolean = true,
    val cardLayoutsVersion: Int = 0,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
    val isLoggedIn: Boolean get() = authToken.isNotBlank()

    /** Lokal eingetragener Key hat Vorrang, sonst der zentral vom Server bezogene. */
    val effectiveWakeWordKey: String get() = picovoiceAccessKey.ifBlank { serverWakeWordKey }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_TIER = intPreferencesKey("user_tier")
        val THEME_NAME = stringPreferencesKey("theme_name")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val VOICE_ID = stringPreferencesKey("voice_id")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_WORD_KEYWORD = stringPreferencesKey("wake_word_keyword")
        val PICOVOICE_KEY = stringPreferencesKey("picovoice_access_key")
        val SERVER_WAKE_KEY = stringPreferencesKey("server_wake_word_key")
        val CUSTOM_PPN_PATH = stringPreferencesKey("custom_wake_word_path")
        val CUSTOM_PV_PATH = stringPreferencesKey("custom_wake_word_model_path")
        val TALK_SILENCE_MS = intPreferencesKey("talk_silence_ms")
        val RELAXED_SECURITY = booleanPreferencesKey("relaxed_security")
        val TTS_FALLBACK = booleanPreferencesKey("tts_fallback")
        val CARD_LAYOUTS_VERSION = intPreferencesKey("card_layouts_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.SERVER_URL] ?: "",
            authToken = p[Keys.AUTH_TOKEN] ?: "",
            userName = p[Keys.USER_NAME] ?: "",
            userTier = p[Keys.USER_TIER] ?: 1,
            themeName = p[Keys.THEME_NAME] ?: "Indigo",
            darkMode = p[Keys.DARK_MODE] ?: "system",
            voiceId = p[Keys.VOICE_ID] ?: "",
            wakeWordEnabled = p[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWordKeyword = p[Keys.WAKE_WORD_KEYWORD] ?: "COMPUTER",
            picovoiceAccessKey = p[Keys.PICOVOICE_KEY] ?: "",
            serverWakeWordKey = p[Keys.SERVER_WAKE_KEY] ?: "",
            customWakeWordPath = p[Keys.CUSTOM_PPN_PATH] ?: "",
            customWakeWordModelPath = p[Keys.CUSTOM_PV_PATH] ?: "",
            talkSilenceMs = p[Keys.TALK_SILENCE_MS] ?: 900,
            relaxedSecurity = p[Keys.RELAXED_SECURITY] ?: false,
            ttsFallbackEnabled = p[Keys.TTS_FALLBACK] ?: true,
            cardLayoutsVersion = p[Keys.CARD_LAYOUTS_VERSION] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Nur für Stellen ohne Coroutine-Kontext (z. B. Service-onCreate). */
    fun currentBlocking(): AppSettings = runBlocking { settings.first() }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url.trim().trimEnd('/') }
    suspend fun setAuth(token: String, userName: String, tier: Int) = edit {
        it[Keys.AUTH_TOKEN] = token
        it[Keys.USER_NAME] = userName
        it[Keys.USER_TIER] = tier
    }

    suspend fun logout() = edit {
        it[Keys.AUTH_TOKEN] = ""
        it[Keys.USER_NAME] = ""
        it[Keys.USER_TIER] = 1
    }

    suspend fun setTheme(name: String) = edit { it[Keys.THEME_NAME] = name }
    suspend fun setDarkMode(mode: String) = edit { it[Keys.DARK_MODE] = mode }
    suspend fun setVoiceId(id: String) = edit { it[Keys.VOICE_ID] = id }
    suspend fun setWakeWordEnabled(enabled: Boolean) = edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    suspend fun setWakeWordKeyword(keyword: String) = edit { it[Keys.WAKE_WORD_KEYWORD] = keyword }
    suspend fun setPicovoiceKey(key: String) = edit { it[Keys.PICOVOICE_KEY] = key }
    suspend fun setServerWakeWordKey(key: String) = edit { it[Keys.SERVER_WAKE_KEY] = key }
    suspend fun setCustomWakeWordPath(path: String) = edit { it[Keys.CUSTOM_PPN_PATH] = path }
    suspend fun setCustomWakeWordModelPath(path: String) = edit { it[Keys.CUSTOM_PV_PATH] = path }
    suspend fun setTalkSilenceMs(ms: Int) = edit { it[Keys.TALK_SILENCE_MS] = ms.coerceIn(300, 5000) }
    suspend fun setRelaxedSecurity(relaxed: Boolean) = edit { it[Keys.RELAXED_SECURITY] = relaxed }
    suspend fun setTtsFallback(enabled: Boolean) = edit { it[Keys.TTS_FALLBACK] = enabled }
    suspend fun setCardLayoutsVersion(v: Int) = edit { it[Keys.CARD_LAYOUTS_VERSION] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
