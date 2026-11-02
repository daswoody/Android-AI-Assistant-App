package de.heimai.app.features.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.heimai.app.HeimAiApp
import de.heimai.app.core.network.AssistantSession
import de.heimai.app.core.network.RemoteMessage
import de.heimai.app.core.network.ToolActivity
import de.heimai.app.core.network.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bindet einen Bildschirm (Chat oder Talk) an eine Sitzung aus dem
 * [de.heimai.app.core.session.SessionHub] und hält sie über Rotationen hinweg.
 *
 * Historie ist **zentral**: Der Orchestrator besitzt alle Gespräche
 * (GET /v1/conversations…), die App ist nur eine Ansicht — dieselbe Historie
 * erscheint im Browser-Frontend und später in der Windows-App. Bei einer
 * bestehenden Konversation lädt das ViewModel die Nachrichten vom Server und
 * setzt das Gespräch über `conversation_id` im hello serverseitig fort.
 *
 * Läuft für das Gespräch noch eine Sitzung (Hintergrund-Agent, v1.29), wird
 * sie übernommen — ihr Verlauf steht dann schon in der Sitzung.
 */
class ChatViewModel(
    application: Application,
    conversationId: String?,
    mode: String,
) : AndroidViewModel(application) {

    private val container = HeimAiApp.from(application).container

    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    private val lease = container.sessions.acquire(mode, conversationId)

    /** Schlüssel der Sitzung im SessionHub (für die Sichtbarkeit). */
    val sessionKey: Long = lease.key

    val session: AssistantSession = lease.session

    init {
        if (conversationId != null && lease.isNew) {
            viewModelScope.launch {
                try {
                    val detail = container.api.conversation(conversationId)
                    session.prependHistory(historyToUi(detail.messages))
                } catch (e: Exception) {
                    _historyError.value = "Verlauf nicht ladbar: ${e.message}"
                }
            }
        }
    }

    override fun onCleared() {
        // Nicht trennen: Der Hub hält die Verbindung offen, solange ein
        // Hintergrund-Agent arbeitet, und schließt sie sonst sofort.
        container.sessions.release(sessionKey)
        super.onCleared()
    }

    companion object {
        fun factory(conversationId: String?, mode: String) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ChatViewModel(app, conversationId, mode)
            }
        }

        /** REST-Verlauf → Anzeige: Chip-Zeile vor der Antwort, dann Text, dann Karten. Negative Ids. */
        fun historyToUi(remote: List<RemoteMessage>): List<UiMessage> {
            var nextId = -1L
            val messages = mutableListOf<UiMessage>()
            remote.forEach { msg ->
                if (msg.tools.isNotEmpty()) {
                    messages += UiMessage(
                        id = nextId--,
                        role = "tool_activity",
                        activities = msg.tools.map { ToolActivity(it.tool, it.status, it.job_id) },
                    )
                }
                if (msg.content.isNotBlank()) {
                    messages += UiMessage(id = nextId--, role = msg.role, text = msg.content)
                }
                msg.cards.forEach { card ->
                    messages += UiMessage(id = nextId--, role = "card", card = card)
                }
            }
            return messages
        }
    }
}
