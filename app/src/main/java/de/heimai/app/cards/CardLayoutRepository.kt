package de.heimai.app.cards

import android.content.Context
import android.util.Log
import de.heimai.app.core.db.CardLayoutDao
import de.heimai.app.core.db.CardLayoutEntity
import de.heimai.app.core.model.LayoutTemplate
import de.heimai.app.core.network.ApiClient
import de.heimai.app.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Zentrale Karten-Verwaltung ("Karten wie Skills"):
 *
 * 1. Built-in-Templates aus assets/cards/*.json (Fallback, offline-fähig)
 * 2. Lokaler Cache in Room (zuletzt vom Server gezogene Layouts)
 * 3. Sync gegen den Card-Layout-Server: GET /v1/cards/layouts
 *    (Teil des Orchestrators, siehe docs/PROTOCOL.md — neue Karten-Layouts
 *    erreichen die App damit OHNE App-Update; gleiches JSON konsumiert
 *    später die Windows-App in Phase 2.5)
 *
 * Unbekannte Kartentypen rendern über das "generic"-Template.
 */
class CardLayoutRepository(
    private val context: Context,
    private val api: ApiClient,
    private val dao: CardLayoutDao,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private var templates: MutableMap<String, LayoutTemplate>? = null

    suspend fun templateFor(cardType: String): LayoutTemplate? {
        val map = loaded()
        return map[cardType] ?: map[GENERIC]
    }

    /** Layout-Update vom Server ziehen (App-Start / Pull-to-Refresh). */
    suspend fun sync() {
        val map = loaded()
        try {
            val since = settings.current().cardLayoutsVersion
            val response = api.cardLayouts(since)
            if (response.layouts.isEmpty()) return
            mutex.withLock {
                response.layouts.forEach { map[it.cardType] = it }
            }
            dao.upsertAll(response.layouts.map {
                CardLayoutEntity(
                    cardType = it.cardType,
                    layoutVersion = it.layoutVersion,
                    templateJson = json.encodeToString(LayoutTemplate.serializer(), it),
                )
            })
            settings.setCardLayoutsVersion(response.version)
        } catch (e: Exception) {
            // Offline / Server ohne Layout-Endpoint: Assets+Cache reichen
            Log.w(TAG, "Card-Layout-Sync übersprungen: ${e.message}")
        }
    }

    private suspend fun loaded(): MutableMap<String, LayoutTemplate> = mutex.withLock {
        templates?.let { return it }
        val map = mutableMapOf<String, LayoutTemplate>()
        withContext(Dispatchers.IO) {
            // 1) Assets
            context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.forEach { file ->
                runCatching {
                    val text = context.assets.open("$ASSET_DIR/$file").bufferedReader().readText()
                    val template = json.decodeFromString(LayoutTemplate.serializer(), text)
                    map[template.cardType] = template
                }.onFailure { Log.w(TAG, "Asset-Template $file fehlerhaft: ${it.message}") }
            }
            // 2) Cache überschreibt Assets, wenn neuer
            dao.all().forEach { entity ->
                runCatching {
                    val template = json.decodeFromString(LayoutTemplate.serializer(), entity.templateJson)
                    val existing = map[template.cardType]
                    if (existing == null || template.layoutVersion >= existing.layoutVersion) {
                        map[template.cardType] = template
                    }
                }
            }
        }
        templates = map
        map
    }

    companion object {
        private const val TAG = "CardLayoutRepo"
        private const val ASSET_DIR = "cards"
        const val GENERIC = "generic"
    }
}
