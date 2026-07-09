package de.heimai.app.features.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.heimai.app.HeimAiApp
import de.heimai.app.core.network.AssistantSession
import de.heimai.app.core.network.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hält die WebSocket-Session über Rotationen hinweg.
 *
 * Historie ist **zentral**: Der Orchestrator besitzt alle Gespräche
 * (GET /v1/conversations…), die App ist nur eine Ansicht — dieselbe Historie
 * erscheint im Browser-Frontend und später in der Windows-App. Bei einer
 * bestehenden Konversation lädt das ViewModel die Nachrichten vom Server und
 * setzt das Gespräch über `conversation_id` im hello serverseitig fort.
 */
class ChatViewModel(
    application: Application,
    conversationId: String?,
    mode: String,
) : AndroidViewModel(application) {

    private val container = HeimAiApp.from(application).container

    private val _history = MutableStateFlow<List<UiMessage>>(emptyList())
    val history: StateFlow<List<UiMessage>> = _history.asStateFlow()

    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    val session: AssistantSession

    init {
        session = AssistantSession(
            scope = viewModelScope,
            client = container.okHttp,
            json = container.json,
            settings = container.settings,
            toolExecutor = container.toolExecutor,
            tts = container.tts,
            audioSession = container.audioSession,
            mode = mode,
            initialConversationId = conversationId,
        )
        session.connect()

        if (conversationId != null) {
            viewModelScope.launch {
                try {
                    val detail = container.api.conversation(conversationId)
                    var nextId = -1L
                    val messages = mutableListOf<UiMessage>()
                    detail.messages.forEach { msg ->
                        if (msg.content.isNotBlank()) {
                            messages += UiMessage(id = nextId--, role = msg.role, text = msg.content)
                        }
                        msg.cards.forEach { card ->
                            messages += UiMessage(id = nextId--, role = "card", card = card)
                        }
                    }
                    _history.value = messages
                } catch (e: Exception) {
                    _historyError.value = "Verlauf nicht ladbar: ${e.message}"
                }
            }
        }
    }

    override fun onCleared() {
        session.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(conversationId: String?, mode: String) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ChatViewModel(app, conversationId, mode)
            }
        }
    }
}
