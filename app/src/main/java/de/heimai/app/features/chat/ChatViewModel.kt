package de.heimai.app.features.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.heimai.app.HeimAiApp
import de.heimai.app.core.db.ConversationRecorder
import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.network.AssistantSession
import de.heimai.app.core.network.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hält die WebSocket-Session über Rotationen hinweg und lädt den
 * persistierten Verlauf einer bestehenden Konversation.
 */
class ChatViewModel(
    application: Application,
    conversationId: Long,
    mode: String,
) : AndroidViewModel(application) {

    private val container = HeimAiApp.from(application).container

    private val _history = MutableStateFlow<List<UiMessage>>(emptyList())
    val history: StateFlow<List<UiMessage>> = _history.asStateFlow()

    val session: AssistantSession

    init {
        val recorder = ConversationRecorder(
            container.database, viewModelScope, container.json,
            source = mode,
            conversationId = conversationId.takeIf { it > 0 },
        )
        session = AssistantSession(
            scope = viewModelScope,
            client = container.okHttp,
            json = container.json,
            settings = container.settings,
            toolExecutor = container.toolExecutor,
            tts = container.tts,
            audioSession = container.audioSession,
            mode = mode,
            listener = recorder,
        )
        session.connect()

        if (conversationId > 0) {
            viewModelScope.launch {
                _history.value = container.database.messageDao()
                    .forConversation(conversationId)
                    .mapIndexed { index, entity ->
                        val card = entity.cardJson?.let {
                            runCatching {
                                container.json.decodeFromString(CardEnvelope.serializer(), it)
                            }.getOrNull()
                        }
                        UiMessage(
                            id = -1000L - index, // negative IDs kollidieren nicht mit Live-Nachrichten
                            role = if (card != null) "card" else entity.role,
                            text = entity.text,
                            card = card,
                        )
                    }
            }
        }
    }

    override fun onCleared() {
        session.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(conversationId: Long, mode: String) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ChatViewModel(app, conversationId, mode)
            }
        }
    }
}
