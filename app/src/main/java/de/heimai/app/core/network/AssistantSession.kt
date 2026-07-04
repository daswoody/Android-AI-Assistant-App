package de.heimai.app.core.network

import android.util.Base64
import de.heimai.app.audio.AudioPlayer
import de.heimai.app.audio.AudioStreamer
import de.heimai.app.audio.TtsFallback
import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.settings.SettingsRepository
import de.heimai.app.tools.DeviceToolExecutor
import de.heimai.app.tools.DeviceToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class UiMessage(
    val id: Long,
    /** user | assistant | card | info */
    val role: String,
    val text: String = "",
    val card: CardEnvelope? = null,
    val final: Boolean = true,
)

data class SessionState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val partialTranscript: String = "",
    val messages: List<UiMessage> = emptyList(),
    val error: String? = null,
)

/**
 * Eine Assistenz-Sitzung gegen den Voice-Orchestrator:
 * WebSocket {server}/v1/assistant/stream (Protokoll: docs/PROTOCOL.md).
 *
 * Übernimmt Audio-Streaming (PCM16/16k rein, PCM16 raus), Transkript- und
 * Text-Streaming, Card-Push und die Tool-Calling-Bridge: Der Server-LLM
 * kann die in [DeviceToolRegistry] deklarierten Geräte-Tools aufrufen,
 * die Ausführung passiert lokal in [DeviceToolExecutor].
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val toolExecutor: DeviceToolExecutor,
    private val tts: TtsFallback,
    /** chat | talk | assist — rein informativ für den Server */
    private val mode: String,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onUserText(text: String) {}
        fun onAssistantText(text: String) {}
        fun onCard(card: CardEnvelope) {}
    }

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private var webSocket: WebSocket? = null
    private var micJob: Job? = null
    private val streamer = AudioStreamer()
    private val player = AudioPlayer()

    /** true, sobald für den aktuellen Turn Server-Audio ankam (kein TTS-Fallback nötig) */
    private var turnHadAudio = false
    private var currentAssistantText = StringBuilder()
    private var currentAssistantMsgId: Long? = null

    fun connect() {
        if (webSocket != null) return
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING, error = null)
        scope.launch {
            val s = settings.current()
            if (s.serverUrl.isBlank()) {
                fail("Kein Server konfiguriert")
                return@launch
            }
            val wsUrl = s.serverUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") + "/v1/assistant/stream"
            val request = Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${s.authToken}")
                .build()
            webSocket = client.newWebSocket(request, SocketListener())
        }
    }

    fun disconnect() {
        stopListening()
        player.release()
        webSocket?.close(1000, "bye")
        webSocket = null
        _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED, listening = false)
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        addMessage(UiMessage(nextId.getAndIncrement(), "user", text))
        listener?.onUserText(text)
        turnHadAudio = false
        send(buildJsonObject {
            put("type", "text_input")
            put("text", text)
        })
    }

    /** Push-to-Talk / Realtime: Mikrofon streamen, bis stopListening() kommt. */
    fun startListening() {
        if (micJob != null) return
        interruptPlayback()
        _state.value = _state.value.copy(listening = true, partialTranscript = "")
        turnHadAudio = false
        micJob = scope.launch {
            try {
                streamer.stream().collect { chunk ->
                    send(buildJsonObject {
                        put("type", "audio_chunk")
                        put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                    })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normales Ende via stopListening() — kein Fehler
                throw e
            } catch (e: Exception) {
                fail("Mikrofon-Fehler: ${e.message}")
            }
        }
    }

    fun stopListening() {
        micJob?.cancel()
        micJob = null
        if (_state.value.listening) {
            send(buildJsonObject { put("type", "audio_end") })
        }
        _state.value = _state.value.copy(listening = false)
    }

    /** Barge-in: laufende Sprachausgabe abbrechen. */
    fun interruptPlayback() {
        player.stop()
        tts.stop()
        if (_state.value.speaking) {
            send(buildJsonObject { put("type", "interrupt") })
        }
        _state.value = _state.value.copy(speaking = false)
    }

    // ---- intern ----

    private fun sendHello() {
        scope.launch {
            val s = settings.current()
            send(buildJsonObject {
                put("type", "hello")
                put("mode", mode)
                put("voice_id", s.voiceId)
                put("device", buildJsonObject {
                    put("platform", "android")
                    put("name", android.os.Build.MODEL)
                    put("app_version", "0.1.0")
                })
                put("capabilities", buildJsonObject {
                    put("audio_in", "pcm16_16000")
                    put("audio_out", "pcm16")
                    put("cards", true)
                })
                put("tools", DeviceToolRegistry.manifest())
            })
        }
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "transcript" -> {
                val t = obj["text"]?.jsonPrimitive?.content.orEmpty()
                val final = obj["final"]?.jsonPrimitive?.boolean ?: false
                if (final) {
                    _state.value = _state.value.copy(partialTranscript = "")
                    if (t.isNotBlank()) {
                        addMessage(UiMessage(nextId.getAndIncrement(), "user", t))
                        listener?.onUserText(t)
                    }
                } else {
                    _state.value = _state.value.copy(partialTranscript = t)
                }
            }
            "assistant_text" -> {
                val t = obj["text"]?.jsonPrimitive?.content.orEmpty()
                val final = obj["final"]?.jsonPrimitive?.boolean ?: false
                if (final) {
                    val full = if (t.isNotBlank()) t else currentAssistantText.toString()
                    upsertAssistantMessage(full, final = true)
                    listener?.onAssistantText(full)
                    currentAssistantText = StringBuilder()
                    currentAssistantMsgId = null
                } else {
                    currentAssistantText.append(t)
                    upsertAssistantMessage(currentAssistantText.toString(), final = false)
                }
            }
            "audio_chunk" -> {
                turnHadAudio = true
                _state.value = _state.value.copy(speaking = true)
                val data = obj["data"]?.jsonPrimitive?.content ?: return
                val rate = obj["sample_rate"]?.jsonPrimitive?.int ?: 24_000
                player.play(Base64.decode(data, Base64.NO_WRAP), rate)
            }
            "audio_end" -> _state.value = _state.value.copy(speaking = false)
            "card" -> {
                val cardObj = obj["card"] ?: return
                runCatching {
                    json.decodeFromJsonElement(CardEnvelope.serializer(), cardObj)
                }.getOrNull()?.let { card ->
                    addMessage(UiMessage(nextId.getAndIncrement(), "card", card = card))
                    listener?.onCard(card)
                }
            }
            "tool_call" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.content ?: return
                val name = obj["name"]?.jsonPrimitive?.content ?: return
                val args = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                scope.launch {
                    val result = toolExecutor.execute(name, args)
                    send(buildJsonObject {
                        put("type", "tool_result")
                        put("call_id", callId)
                        put("ok", result.ok)
                        put("result", result.payload)
                    })
                }
            }
            "done" -> {
                _state.value = _state.value.copy(speaking = false)
                maybeTtsFallback()
            }
            "error" -> fail(obj["message"]?.jsonPrimitive?.content ?: "Unbekannter Server-Fehler")
        }
    }

    /** Kam für diesen Turn kein Server-Audio, lokal vorlesen (Settings-abhängig). */
    private fun maybeTtsFallback() {
        if (turnHadAudio) return
        val lastAssistant = _state.value.messages.lastOrNull { it.role == "assistant" } ?: return
        scope.launch {
            if (settings.current().ttsFallbackEnabled) tts.speak(lastAssistant.text)
        }
    }

    private fun upsertAssistantMessage(text: String, final: Boolean) {
        val id = currentAssistantMsgId
        if (id == null) {
            val newId = nextId.getAndIncrement()
            currentAssistantMsgId = newId
            addMessage(UiMessage(newId, "assistant", text, final = final))
        } else {
            _state.value = _state.value.copy(
                messages = _state.value.messages.map {
                    if (it.id == id) it.copy(text = text, final = final) else it
                }
            )
        }
    }

    private fun addMessage(message: UiMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + message)
    }

    private fun send(payload: JsonObject) {
        webSocket?.send(payload.toString())
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(connection = ConnectionState.ERROR, error = message)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = _state.value.copy(connection = ConnectionState.CONNECTED, error = null)
            sendHello()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@AssistantSession.webSocket = null
            fail("Verbindung fehlgeschlagen: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@AssistantSession.webSocket = null
            _state.value = _state.value.copy(connection = ConnectionState.DISCONNECTED)
        }
    }
}
