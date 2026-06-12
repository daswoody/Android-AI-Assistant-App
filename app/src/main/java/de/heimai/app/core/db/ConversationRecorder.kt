package de.heimai.app.core.db

import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.network.AssistantSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Persistiert eine laufende Assistenz-Sitzung als Konversation in Room,
 * damit sie später im Startscreen unter "Vergangene Gespräche" auftaucht.
 * Wird als AssistantSession.Listener an Chat, Talk und Assistant-Overlay
 * gehängt. Die Konversation wird lazy beim ersten Inhalt angelegt.
 */
class ConversationRecorder(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val json: Json,
    private val source: String,
    private var conversationId: Long? = null,
) : AssistantSession.Listener {

    private val mutex = Mutex()

    override fun onUserText(text: String) = record("user", text, null)
    override fun onAssistantText(text: String) = record("assistant", text, null)
    override fun onCard(card: CardEnvelope) =
        record("card", card.title ?: card.type, json.encodeToString(CardEnvelope.serializer(), card))

    fun currentConversationId(): Long? = conversationId

    private fun record(role: String, text: String, cardJson: String?) {
        scope.launch {
            val now = System.currentTimeMillis()
            val id = mutex.withLock {
                conversationId ?: run {
                    val title = text.take(60).ifBlank { "Neue Unterhaltung" }
                    database.conversationDao()
                        .upsert(ConversationEntity(title = title, source = source, createdAt = now, updatedAt = now))
                        .also { conversationId = it }
                }
            }
            database.messageDao().insert(
                MessageEntity(conversationId = id, role = role, text = text, cardJson = cardJson, createdAt = now)
            )
            val existing = database.conversationDao().get(id)
            database.conversationDao().touch(id, existing?.title ?: text.take(60), now)
        }
    }
}
